package com.zhiyu.health.service;

import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppointmentConcurrencyTest {

    @Test
    void concurrentPatientsCompetingForLastSlotProduceExactlyOneAppointment() throws Exception {
        AppointmentMapper appointments = mock(AppointmentMapper.class);
        ScheduleMapper schedules = mock(ScheduleMapper.class);
        InMemorySlotCounter redis = new InMemorySlotCounter();
        AtomicInteger pgRemaining = new AtomicInteger(1);
        AtomicInteger inserts = new AtomicInteger();
        ConcurrentHashMap<Long, Appointment> saved = new ConcurrentHashMap<>();
        when(schedules.selectByIdForUpdate(9L)).thenAnswer(invocation -> schedule(pgRemaining.get()));
        when(schedules.decrementRemainingSlots(9L)).thenAnswer(invocation ->
                pgRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0 ? 1 : 0);
        when(appointments.nextSequenceNumber(9L)).thenReturn(1);
        when(appointments.insert(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appointment = invocation.getArgument(0);
            appointment.setId(21L);
            saved.put(21L, appointment);
            inserts.incrementAndGet();
            return 1;
        });
        when(appointments.selectViewById(21L)).thenAnswer(invocation -> {
            Appointment appointment = saved.get(21L);
            appointment.setDoctorName("周安宁");
            return appointment;
        });
        redis.initialize(9L, 1);
        AppointmentService service = new AppointmentService(
                appointments, schedules, redis, serializedTransaction());
        AtomicInteger successes = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(10);
        try {
            for (long patientId = 1; patientId <= 10; patientId++) {
                long currentPatient = patientId;
                executor.submit(() -> {
                    try {
                        service.create(currentPatient, 7L, 9L, "摘要");
                        successes.incrementAndGet();
                    } catch (RuntimeException ignored) {
                        // 售罄是并发竞争的预期外部结果。
                    }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).hasValue(1);
        assertThat(inserts).hasValue(1);
        assertThat(redis.values.get(9L)).hasValue(0);
        assertThat(pgRemaining).hasValue(0);
    }

    private TransactionTemplate serializedTransaction() {
        Object rowLock = new Object();
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            synchronized (rowLock) {
                return callback.doInTransaction(mock(TransactionStatus.class));
            }
        });
        return template;
    }

    private Schedule schedule(int remaining) {
        Schedule schedule = new Schedule();
        schedule.setId(9L);
        schedule.setTotalSlots(1);
        schedule.setRemainingSlots(remaining);
        schedule.setIsActive(true);
        return schedule;
    }
}
