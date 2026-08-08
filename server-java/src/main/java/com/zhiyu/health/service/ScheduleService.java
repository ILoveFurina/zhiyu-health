package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 排班管理：CRUD 由 ServiceImpl 提供；号源计数与 PG 的双写一致性统一走 SlotAccounting。 */
@Service
@RequiredArgsConstructor
public class ScheduleService extends ServiceImpl<ScheduleMapper, Schedule> {

    private final DoctorMapper doctorMapper;
    private final SlotAccounting slotAccounting;
    private final TransactionTemplate transactionTemplate;

    public List<Schedule> listSchedules() {
        return list(new QueryWrapper<Schedule>().orderByAsc("schedule_date", "time_slot", "id"));
    }

    public Schedule getSchedule(long scheduleId) {
        Schedule schedule = getById(scheduleId);
        if (schedule == null) {
            throw new ApiException(404, "排班不存在");
        }
        return schedule;
    }

    public Schedule createSchedule(Schedule schedule) {
        if (doctorMapper.selectById(schedule.getDoctorId()) == null) {
            throw new ApiException(404, "医生不存在");
        }
        schedule.setRemainingSlots(schedule.getTotalSlots());
        schedule.setIsActive(true);
        // withInitialization 的补偿范围覆盖整个事务（含提交失败）：已初始化未提交即删除 Redis 计数。
        return slotAccounting.withInitialization(init -> transactionTemplate.execute(status -> {
            baseMapper.insert(schedule);
            init.init(schedule.getId(), schedule.getRemainingSlots());
            return schedule;
        }));
    }

    public Schedule updateSchedule(Schedule changes) {
        if (doctorMapper.selectById(changes.getDoctorId()) == null) {
            throw new ApiException(404, "排班或医生不存在");
        }
        // withAdjustment 的补偿范围覆盖整个事务（含提交失败）：已调整未提交即按已应用增量反向补偿。
        return slotAccounting.withAdjustment(
                changes.getId(),
                adjustment -> transactionTemplate.execute(status -> {
                    // 锁必须覆盖读取与 delta 计算，使并发容量更新基于最新已提交总量。
                    Schedule current = baseMapper.selectByIdForUpdate(changes.getId());
                    if (current == null) {
                        throw new ApiException(404, "排班或医生不存在");
                    }
                    int usedSlots = current.getTotalSlots() - current.getRemainingSlots();
                    if (changes.getTotalSlots() < usedSlots) {
                        throw new ApiException(409, "号源总数不能小于已使用号源数");
                    }
                    int capacityDelta = changes.getTotalSlots() - current.getTotalSlots();
                    if (baseMapper.adjustCapacity(changes) != 1) {
                        throw new ApiException(409, "号源总数不能小于已使用号源数");
                    }
                    adjustment.apply(capacityDelta);
                    return baseMapper.selectById(changes.getId());
                }));
    }

    public Schedule disableSchedule(long scheduleId) {
        Schedule schedule = getById(scheduleId);
        if (schedule == null) {
            throw new ApiException(404, "排班不存在");
        }
        // 仅更新状态列，避免把查询后已被并发扣减的 remaining_slots 整行写回。
        baseMapper.disable(scheduleId);
        schedule.setIsActive(false);
        return schedule;
    }

    /** 恢复出诊：与 disableSchedule 互为逆操作，号源 remaining_slots 保持原值不变（停诊期间冻结）。 */
    public Schedule enableSchedule(long scheduleId) {
        Schedule schedule = getById(scheduleId);
        if (schedule == null) {
            throw new ApiException(404, "排班不存在");
        }
        baseMapper.enable(scheduleId);
        schedule.setIsActive(true);
        return schedule;
    }

    public boolean tryDecrementSlot(long scheduleId) {
        // 预扣失败（售罄）或 PG 对账失败均由 SlotAccounting 回补 Redis；PG 写入在事务内执行。
        return slotAccounting.tryDeduct(
                scheduleId,
                () -> transactionTemplate.execute(status -> baseMapper.decrementRemainingSlots(scheduleId) == 1));
    }
}
