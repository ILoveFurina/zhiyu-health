package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;
    private final DoctorMapper doctorMapper;
    private final SlotCounter slotCounter;
    private final TransactionTemplate transactionTemplate;

    public ScheduleService(ScheduleMapper scheduleMapper,
                           DoctorMapper doctorMapper,
                           SlotCounter slotCounter,
                           TransactionTemplate transactionTemplate) {
        this.scheduleMapper = scheduleMapper;
        this.doctorMapper = doctorMapper;
        this.slotCounter = slotCounter;
        this.transactionTemplate = transactionTemplate;
    }

    public List<Schedule> listSchedules() {
        return scheduleMapper.selectList(new QueryWrapper<Schedule>()
                .orderByAsc("schedule_date", "time_slot", "id"));
    }

    public Schedule getSchedule(long scheduleId) {
        return scheduleMapper.selectById(scheduleId);
    }

    public Schedule createSchedule(Schedule schedule) {
        if (doctorMapper.selectById(schedule.getDoctorId()) == null) {
            return null;
        }
        schedule.setRemainingSlots(schedule.getTotalSlots());
        schedule.setIsActive(true);
        // Redis 不参与 PG 事务；先初始化计数，提交失败后再删除，避免留下可预约的孤儿号源池。
        try {
            return transactionTemplate.execute(status -> {
                scheduleMapper.insert(schedule);
                slotCounter.initialize(schedule.getId(), schedule.getRemainingSlots());
                return schedule;
            });
        } catch (RuntimeException exception) {
            if (schedule.getId() != null) {
                slotCounter.delete(schedule.getId());
            }
            throw exception;
        }
    }

    public Schedule updateSchedule(Schedule changes) {
        if (doctorMapper.selectById(changes.getDoctorId()) == null) {
            return null;
        }
        AtomicBoolean counterAdjusted = new AtomicBoolean();
        AtomicInteger appliedDelta = new AtomicInteger();
        try {
            return transactionTemplate.execute(status -> {
                // 锁必须覆盖读取与 delta 计算，使并发容量更新基于最新已提交总量。
                Schedule current = scheduleMapper.selectByIdForUpdate(changes.getId());
                if (current == null) {
                    return null;
                }
                int usedSlots = current.getTotalSlots() - current.getRemainingSlots();
                if (changes.getTotalSlots() < usedSlots) {
                    throw new ScheduleCapacityException();
                }
                int capacityDelta = changes.getTotalSlots() - current.getTotalSlots();
                if (scheduleMapper.adjustCapacity(changes) != 1) {
                    throw new ScheduleCapacityException();
                }
                if (capacityDelta != 0) {
                    // INCRBY 与预约 DECR 可交换，避免用旧快照覆盖并发扣减。
                    slotCounter.adjust(changes.getId(), capacityDelta);
                    appliedDelta.set(capacityDelta);
                    counterAdjusted.set(true);
                }
                return scheduleMapper.selectById(changes.getId());
            });
        } catch (RuntimeException exception) {
            if (counterAdjusted.get()) {
                // Redis 不随 PG 回滚，只反向补偿本事务实际应用的增量。
                slotCounter.adjust(changes.getId(), -appliedDelta.get());
            }
            throw exception;
        }
    }

    public Schedule disableSchedule(long scheduleId) {
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            return null;
        }
        // 仅更新状态列，避免把查询后已被并发扣减的 remaining_slots 整行写回。
        scheduleMapper.disable(scheduleId);
        schedule.setIsActive(false);
        return schedule;
    }

    public boolean tryDecrementSlot(long scheduleId) {
        long redisRemaining = slotCounter.decrement(scheduleId);
        if (redisRemaining < 0) {
            slotCounter.increment(scheduleId);
            return false;
        }
        try {
            Boolean decremented = transactionTemplate.execute(status ->
                    scheduleMapper.decrementRemainingSlots(scheduleId) == 1);
            if (!Boolean.TRUE.equals(decremented)) {
                slotCounter.increment(scheduleId);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            // PG 未提交时返还 Redis 预扣，维持两个计数源一致。
            slotCounter.increment(scheduleId);
            throw exception;
        }
    }
}
