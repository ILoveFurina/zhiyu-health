package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ScheduleServiceTest {

    private final ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
    private final DoctorMapper doctorMapper = mock(DoctorMapper.class);
    private final InMemorySlotCounter slotCounter = new InMemorySlotCounter();

    @Test
    void createSetsRemainingSlotsAndInitializesCounter() {
        when(doctorMapper.selectById(1L)).thenReturn(new Doctor());
        when(scheduleMapper.insert(any(Schedule.class))).thenAnswer(invocation -> {
            Schedule schedule = invocation.getArgument(0);
            schedule.setId(7L);
            return 1;
        });
        ScheduleService service = serviceWithImmediateTransaction();
        Schedule input = new Schedule();
        input.setDoctorId(1L);
        input.setTotalSlots(8);

        Schedule created = service.createSchedule(input);

        assertThat(created.getRemainingSlots()).isEqualTo(8);
        assertThat(created.getIsActive()).isTrue();
        assertThat(slotCounter.values.get(7L)).hasValue(8);
    }

    @Test
    void createRejectsWhenDoctorIsMissing() {
        ScheduleService service = serviceWithImmediateTransaction();
        Schedule input = new Schedule();
        input.setDoctorId(99L);
        input.setTotalSlots(8);

        assertThatThrownBy(() -> service.createSchedule(input))
                .isInstanceOf(ApiException.class)
                .hasMessage("医生不存在");
        verify(scheduleMapper, never()).insert(any(Schedule.class));
        assertThat(slotCounter.values).isEmpty();
    }

    @Test
    void createInitializesRedisCounterAndCleansItWhenDatabaseCommitFails() {
        when(doctorMapper.selectById(1L)).thenReturn(new Doctor());
        when(scheduleMapper.insert(any(Schedule.class))).thenAnswer(invocation -> {
            Schedule schedule = invocation.getArgument(0);
            schedule.setId(7L);
            return 1;
        });
        TransactionTemplate failingTransaction = transaction(callback -> {
            callback.doInTransaction(mock(TransactionStatus.class));
            throw new IllegalStateException("模拟数据库提交失败");
        });
        ScheduleService service = serviceWith(failingTransaction);
        Schedule input = new Schedule();
        input.setDoctorId(1L);
        input.setTotalSlots(8);

        assertThatThrownBy(() -> service.createSchedule(input)).isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values).isEmpty();
    }

    @Test
    void concurrentDecrementOfLastSlotSucceedsExactlyOnceWithoutOverselling() throws Exception {
        AtomicInteger pgRemaining = new AtomicInteger(1);
        when(scheduleMapper.decrementRemainingSlots(1L))
                .thenAnswer(invocation -> pgRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0 ? 1 : 0);
        slotCounter.initialize(1L, 1);
        ScheduleService service =
                serviceWith(transaction(callback -> callback.doInTransaction(mock(TransactionStatus.class))));
        AtomicInteger successes = new AtomicInteger();

        var executor = Executors.newFixedThreadPool(10);
        try {
            for (int index = 0; index < 10; index++) {
                executor.submit(() -> {
                    if (service.tryDecrementSlot(1L)) {
                        successes.incrementAndGet();
                    }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).hasValue(1);
        assertThat(slotCounter.values.get(1L)).hasValue(0);
        assertThat(pgRemaining).hasValue(0);
    }

    @Test
    void soldOutRedisCounterDoesNotTouchPostgres() {
        slotCounter.initialize(1L, 0);
        ScheduleService service = serviceWithImmediateTransaction();

        assertThat(service.tryDecrementSlot(1L)).isFalse();
        assertThat(slotCounter.values.get(1L)).hasValue(0);
        verify(scheduleMapper, never()).decrementRemainingSlots(1L);
    }

    @Test
    void postgresFailureRefundsRedisPredecrement() {
        slotCounter.initialize(1L, 1);
        when(scheduleMapper.decrementRemainingSlots(1L)).thenThrow(new IllegalStateException("模拟 PG 失败"));
        ScheduleService service = serviceWithImmediateTransaction();

        assertThatThrownBy(() -> service.tryDecrementSlot(1L)).isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(1L)).hasValue(1);
    }

    @Test
    void updatePreservesUsedSlotsAndRejectsCapacityBelowUsage() {
        Schedule current = new Schedule();
        current.setId(1L);
        current.setDoctorId(1L);
        current.setTotalSlots(20);
        current.setRemainingSlots(12);
        current.setIsActive(true);
        Schedule expandedResult = schedule(1L, 24, 16);
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(current, expandedResult);
        when(scheduleMapper.selectById(1L)).thenReturn(expandedResult);
        when(doctorMapper.selectById(1L)).thenReturn(new Doctor());
        when(scheduleMapper.adjustCapacity(any(Schedule.class))).thenReturn(1);
        slotCounter.initialize(1L, 12);
        ScheduleService service = serviceWithImmediateTransaction();
        Schedule expanded = new Schedule();
        expanded.setId(1L);
        expanded.setDoctorId(1L);
        expanded.setTotalSlots(24);

        Schedule updated = service.updateSchedule(expanded);

        assertThat(updated.getRemainingSlots()).isEqualTo(16);
        assertThat(slotCounter.values.get(1L)).hasValue(16);

        Schedule tooSmall = new Schedule();
        tooSmall.setId(1L);
        tooSmall.setDoctorId(1L);
        tooSmall.setTotalSlots(7);
        assertThatThrownBy(() -> service.updateSchedule(tooSmall))
                .isInstanceOf(ApiException.class)
                .hasMessage("号源总数不能小于已使用号源数");
    }

    @Test
    void capacityIncreaseAndSlotDecrementDoNotOverwriteEachOther() throws Exception {
        AtomicInteger pgTotal = new AtomicInteger(20);
        AtomicInteger pgRemaining = new AtomicInteger(1);
        when(doctorMapper.selectById(1L)).thenReturn(new Doctor());
        when(scheduleMapper.selectByIdForUpdate(1L))
                .thenAnswer(invocation -> schedule(1L, pgTotal.get(), pgRemaining.get()));
        when(scheduleMapper.selectById(1L)).thenAnswer(invocation -> schedule(1L, pgTotal.get(), pgRemaining.get()));
        when(scheduleMapper.decrementRemainingSlots(1L))
                .thenAnswer(invocation -> pgRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0 ? 1 : 0);
        when(scheduleMapper.adjustCapacity(any(Schedule.class))).thenAnswer(invocation -> {
            Schedule changes = invocation.getArgument(0);
            int delta = changes.getTotalSlots() - pgTotal.getAndSet(changes.getTotalSlots());
            pgRemaining.addAndGet(delta);
            return 1;
        });
        slotCounter.initialize(1L, 1);
        ScheduleService service = serviceWithImmediateTransaction();
        Schedule expanded = schedule(1L, 24, null);
        AtomicInteger successfulDecrements = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> service.updateSchedule(expanded));
            executor.submit(() -> {
                if (service.tryDecrementSlot(1L)) {
                    successfulDecrements.incrementAndGet();
                }
            });
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successfulDecrements).hasValue(1);
        assertThat(pgRemaining).hasValue(4);
        assertThat(slotCounter.values.get(1L)).hasValue(4);
    }

    private Schedule schedule(long id, int totalSlots, Integer remainingSlots) {
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setDoctorId(1L);
        schedule.setTotalSlots(totalSlots);
        schedule.setRemainingSlots(remainingSlots);
        schedule.setIsActive(true);
        return schedule;
    }

    private ScheduleService serviceWithImmediateTransaction() {
        return serviceWith(transaction(callback -> callback.doInTransaction(mock(TransactionStatus.class))));
    }

    private ScheduleService serviceWith(TransactionTemplate template) {
        ScheduleService service = new ScheduleService(doctorMapper, new SlotAccounting(slotCounter), template);
        // ServiceImpl 的 baseMapper 由 Spring 字段注入；直接 new 时需手动挂上 mock mapper
        ReflectionTestUtils.setField(service, "baseMapper", scheduleMapper);
        return service;
    }

    private TransactionTemplate transaction(TransactionExecutor executor) {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return executor.execute(callback);
        });
        return template;
    }

    @FunctionalInterface
    private interface TransactionExecutor {
        Object execute(TransactionCallback<?> callback);
    }
}
