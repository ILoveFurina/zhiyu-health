package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

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
        Schedule current = scheduleMapper.selectById(changes.getId());
        if (current == null || doctorMapper.selectById(changes.getDoctorId()) == null) {
            return null;
        }
        int usedSlots = current.getTotalSlots() - current.getRemainingSlots();
        if (changes.getTotalSlots() < usedSlots) {
            throw new ScheduleCapacityException();
        }
        changes.setRemainingSlots(changes.getTotalSlots() - usedSlots);
        changes.setIsActive(current.getIsActive());
        try {
            return transactionTemplate.execute(status -> {
                scheduleMapper.updateById(changes);
                slotCounter.set(changes.getId(), changes.getRemainingSlots());
                return changes;
            });
        } catch (RuntimeException exception) {
            slotCounter.set(current.getId(), current.getRemainingSlots());
            throw exception;
        }
    }

    public Schedule disableSchedule(long scheduleId) {
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            return null;
        }
        schedule.setIsActive(false);
        scheduleMapper.updateById(schedule);
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
