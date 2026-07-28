package com.zhiyu.health.service;

import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleCapacityConcurrencyTest {

    @Test
    void disableDoesNotWriteBackAStaleRemainingSlotCount() {
        ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
        Schedule current = schedule(20, 5);
        when(scheduleMapper.selectById(1L)).thenReturn(current);
        ScheduleService service = new ScheduleService(
                scheduleMapper, mock(DoctorMapper.class), new InMemorySlotCounter(),
                mock(TransactionTemplate.class));

        assertThat(service.disableSchedule(1L).getIsActive()).isFalse();
        verify(scheduleMapper).disable(1L);
        verify(scheduleMapper, never()).updateById(any(Schedule.class));
    }

    @Test
    void concurrentCapacityUpdatesUseLatestLockedTotalForRedisDelta() throws Exception {
        ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
        DoctorMapper doctorMapper = mock(DoctorMapper.class);
        InMemorySlotCounter slotCounter = new InMemorySlotCounter();
        AtomicInteger pgTotal = new AtomicInteger(20);
        AtomicInteger pgRemaining = new AtomicInteger(5);
        when(doctorMapper.selectById(1L)).thenReturn(new Doctor());
        when(scheduleMapper.selectByIdForUpdate(1L)).thenAnswer(invocation ->
                schedule(pgTotal.get(), pgRemaining.get()));
        when(scheduleMapper.selectById(1L)).thenAnswer(invocation ->
                schedule(pgTotal.get(), pgRemaining.get()));
        when(scheduleMapper.adjustCapacity(any(Schedule.class))).thenAnswer(invocation -> {
            Schedule changes = invocation.getArgument(0);
            int delta = changes.getTotalSlots() - pgTotal.getAndSet(changes.getTotalSlots());
            pgRemaining.addAndGet(delta);
            return 1;
        });
        slotCounter.initialize(1L, 5);
        Object rowLock = new Object();
        ScheduleService service = new ScheduleService(
                scheduleMapper, doctorMapper, slotCounter, serializedTransaction(rowLock));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.updateSchedule(schedule(24, null)));
            var second = executor.submit(() -> service.updateSchedule(schedule(22, null)));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(pgRemaining.get()).isEqualTo(5 + pgTotal.get() - 20);
        assertThat(slotCounter.values.get(1L)).hasValue(pgRemaining.get());
    }

    private TransactionTemplate serializedTransaction(Object rowLock) {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            synchronized (rowLock) {
                return callback.doInTransaction(mock(TransactionStatus.class));
            }
        });
        return template;
    }

    private Schedule schedule(int totalSlots, Integer remainingSlots) {
        Schedule schedule = new Schedule();
        schedule.setId(1L);
        schedule.setDoctorId(1L);
        schedule.setTotalSlots(totalSlots);
        schedule.setRemainingSlots(remainingSlots);
        schedule.setIsActive(true);
        return schedule;
    }
}
