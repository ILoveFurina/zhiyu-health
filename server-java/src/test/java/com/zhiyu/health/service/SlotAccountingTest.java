package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.service.scheduling.SlotAccounting;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 号源记账：五个命令的成功路径与失败补偿（Redis 与 PG 双写一致性收口） */
class SlotAccountingTest {

    private final InMemorySlotCounter slotCounter = new InMemorySlotCounter();
    private final SlotAccounting accounting = new SlotAccounting(slotCounter);

    @Test
    void tryDeductReturnsFalseOnSoldOutWithoutTouchingPgWrite() {
        slotCounter.initialize(1L, 0);
        AtomicBoolean pgRan = new AtomicBoolean();

        assertThat(accounting.tryDeduct(1L, () -> pgRan.getAndSet(true))).isFalse();
        assertThat(pgRan).isFalse();
        assertThat(slotCounter.values.get(1L)).hasValue(0);
    }

    @Test
    void tryDeductRefundsWhenPgWriteReportsSoldOut() {
        slotCounter.initialize(1L, 1);

        assertThat(accounting.tryDeduct(1L, () -> false)).isFalse();
        assertThat(slotCounter.values.get(1L)).hasValue(1);
    }

    @Test
    void tryDeductRefundsAndRethrowsWhenPgWriteFails() {
        slotCounter.initialize(1L, 1);

        assertThatThrownBy(() -> accounting.tryDeduct(1L, () -> {
                    throw new IllegalStateException("模拟 PG 失败");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(1L)).hasValue(1);
    }

    @Test
    void deductionRollsBackOnlyWhenAcquired() {
        slotCounter.initialize(1L, 1);

        assertThatThrownBy(() -> accounting.withDeduction(1L, deduction -> {
                    deduction.acquire();
                    throw new IllegalStateException("模拟提交失败");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(1L)).hasValue(1);

        assertThatThrownBy(() -> accounting.withDeduction(1L, deduction -> {
                    deduction.acquire();
                    throw new IllegalStateException("再次预扣后失败");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(1L)).hasValue(1);
    }

    @Test
    void deductionSoldOutThrowsConflictWithoutDoubleRefund() {
        slotCounter.initialize(1L, 0);

        assertThatThrownBy(() -> accounting.withDeduction(1L, deduction -> {
                    deduction.acquire();
                    return null;
                }))
                .isInstanceOf(ApiException.class)
                .hasMessage("号源已约满");
        assertThat(slotCounter.values.get(1L)).hasValue(0);
    }

    @Test
    void refundRevokesOnlyWhenGranted() {
        slotCounter.initialize(1L, 2);

        assertThatThrownBy(() -> accounting.withRefund(refund -> {
                    refund.grant(1L);
                    throw new IllegalStateException("模拟提交失败");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(1L)).hasValue(2);

        assertThatThrownBy(() -> accounting.withRefund(refund -> {
                    throw new IllegalStateException("未退还即失败");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(1L)).hasValue(2);
    }

    @Test
    void adjustmentRevokesOnlyAppliedDelta() {
        slotCounter.initialize(1L, 12);

        assertThatThrownBy(() -> accounting.withAdjustment(1L, adjustment -> {
                    adjustment.apply(0);
                    adjustment.apply(4);
                    throw new IllegalStateException("模拟提交失败");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values.get(1L)).hasValue(12);
    }

    @Test
    void initializationCleansUpOnlyWhenInitialized() {
        assertThatThrownBy(() -> accounting.withInitialization(init -> {
                    throw new IllegalStateException("未初始化即失败");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values).isEmpty();

        assertThatThrownBy(() -> accounting.withInitialization(init -> {
                    init.init(7L, 8);
                    throw new IllegalStateException("模拟提交失败");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slotCounter.values).isEmpty();
    }
}
