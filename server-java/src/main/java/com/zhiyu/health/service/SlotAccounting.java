package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 号源记账：Redis 计数与 PostgreSQL 持久化的双写一致性收口。
 * Redis 不参与 PG 事务，因此所有"Redis 号源操作 + PG 写入"的序列都经本组件执行：
 * 命令方法先执行 Redis 原子操作（或在事务体内由句柄执行），PG 写入以 lambda 传入、
 * 在调用方事务内运行；一旦 PG 侧抛出（含事务回滚与提交失败），对 Redis 做反向补偿，
 * 只撤销本次已经成功应用的 Redis 变更，维持两个计数源一致。
 */
@Component
@RequiredArgsConstructor
public class SlotAccounting {

    private final SlotCounter slotCounter;

    /**
     * 预扣一个号源并执行 PG 写入，任一层失败即回补 Redis 预扣。
     *
     * @return false 表示售罄（Redis 判负或 PG 对账未命中，均未留下副作用）；true 表示预扣与 PG 写入都已执行
     */
    public boolean tryDeduct(long scheduleId, Supplier<Boolean> pgWrite) {
        long redisRemaining = slotCounter.decrement(scheduleId);
        if (redisRemaining < 0) {
            // DECR 判负：原子回补本次预扣后返回售罄，PG 全程未被触碰。
            slotCounter.increment(scheduleId);
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(pgWrite.get())) {
                // PG 对账售罄（事务正常结束、无数据变更）：回补 Redis 预扣，避免两边计数漂移。
                slotCounter.increment(scheduleId);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            // PG 未提交时回补 Redis 预扣，维持两个计数源一致。
            slotCounter.increment(scheduleId);
            throw exception;
        }
    }

    /**
     * 在 Redis 预扣保护下执行 PG 事务体。事务体内调 {@link Deduction#acquire()} 实际扣减；
     * 事务体抛出（含提交失败）时，仅当已扣减成功才回补 Redis 预扣。
     */
    public <T> T withDeduction(long scheduleId, Function<Deduction, T> txBody) {
        Deduction deduction = new Deduction(scheduleId);
        try {
            return txBody.apply(deduction);
        } catch (RuntimeException exception) {
            deduction.rollback();
            throw exception;
        }
    }

    /**
     * 在 Redis 退还保护下执行 PG 事务体。事务体内调 {@link Refund#grant(long)} 实际退还；
     * 事务体抛出（含提交失败）时，仅当已退还成功才撤销退还。
     */
    public <T> T withRefund(Function<Refund, T> txBody) {
        Refund refund = new Refund();
        try {
            return txBody.apply(refund);
        } catch (RuntimeException exception) {
            refund.revoke();
            throw exception;
        }
    }

    /**
     * 在 Redis 增量调整保护下执行 PG 事务体。事务体内调 {@link Adjustment#apply(int)} 实际调整；
     * 事务体抛出（含提交失败）时，按已应用的增量反向补偿。
     */
    public <T> T withAdjustment(long scheduleId, Function<Adjustment, T> txBody) {
        Adjustment adjustment = new Adjustment(scheduleId);
        try {
            return txBody.apply(adjustment);
        } catch (RuntimeException exception) {
            adjustment.revoke();
            throw exception;
        }
    }

    /**
     * 在 Redis 计数初始化保护下执行 PG 事务体。事务体内调 {@link Initialization#init(long, int)} 实际初始化；
     * 事务体抛出（含提交失败）时清理已初始化的计数。
     */
    public <T> T withInitialization(Function<Initialization, T> txBody) {
        Initialization initialization = new Initialization();
        try {
            return txBody.apply(initialization);
        } catch (RuntimeException exception) {
            initialization.cleanup();
            throw exception;
        }
    }

    /** 预扣句柄：记录本事务已成功的 Redis 扣减，供失败补偿判断。 */
    public final class Deduction {
        private final long scheduleId;
        private boolean acquired;

        private Deduction(long scheduleId) {
            this.scheduleId = scheduleId;
        }

        /** Redis 原子 DECR 预扣一个号源；判负即原子回补并抛出售罄，PG 侧尚未被触碰。 */
        public void acquire() {
            long redisRemaining = slotCounter.decrement(scheduleId);
            if (redisRemaining < 0) {
                slotCounter.increment(scheduleId);
                throw new ApiException(409, "号源已约满");
            }
            acquired = true;
        }

        private void rollback() {
            if (acquired) {
                // Redis 不参与 PG 事务；PG 回滚或提交失败时只回补本次已经成功的预扣。
                slotCounter.increment(scheduleId);
            }
        }
    }

    /** 退还句柄：记录本事务已成功的 Redis 回补，供失败撤销判断。 */
    public final class Refund {
        private Long refundedScheduleId;

        private Refund() {}

        /** 向指定排班的号源池退还一个号源（Redis 原子 INCR）。 */
        public void grant(long scheduleId) {
            slotCounter.increment(scheduleId);
            refundedScheduleId = scheduleId;
        }

        private void revoke() {
            if (refundedScheduleId != null) {
                // 提交失败时撤销 Redis 回补，避免 PG 已回滚而号源池被重复增加。
                slotCounter.decrement(refundedScheduleId);
            }
        }
    }

    /** 容量调整句柄：记录本事务已应用的 Redis 增量，供失败反向补偿。 */
    public final class Adjustment {
        private final long scheduleId;
        private int appliedDelta;

        private Adjustment(long scheduleId) {
            this.scheduleId = scheduleId;
        }

        /** 按 delta 调整号源计数（INCRBY 与预约 DECR 可交换，避免用旧快照覆盖并发扣减）；delta 为 0 时不动作。 */
        public void apply(int delta) {
            if (delta == 0) {
                return;
            }
            slotCounter.adjust(scheduleId, delta);
            appliedDelta = delta;
        }

        private void revoke() {
            if (appliedDelta != 0) {
                // Redis 不随 PG 回滚，只反向补偿本事务实际应用的增量。
                slotCounter.adjust(scheduleId, -appliedDelta);
            }
        }
    }

    /** 初始化句柄：记录本事务已初始化的 Redis 计数，供失败清理。 */
    public final class Initialization {
        private Long initializedScheduleId;

        private Initialization() {}

        /** 初始化指定排班的号源计数；先记录排班 ID，使初始化本身失败时清理分支也能命中。 */
        public void init(long scheduleId, int remainingSlots) {
            initializedScheduleId = scheduleId;
            slotCounter.initialize(scheduleId, remainingSlots);
        }

        private void cleanup() {
            if (initializedScheduleId != null) {
                // Redis 不参与 PG 事务；提交失败后删除计数，避免留下可预约的孤儿号源池。
                slotCounter.delete(initializedScheduleId);
            }
        }
    }
}
