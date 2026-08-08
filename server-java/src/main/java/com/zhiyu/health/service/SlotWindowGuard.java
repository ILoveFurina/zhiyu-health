package com.zhiyu.health.service;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Schedule;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 排班时段截止判断（号源硬约束）：排班当天且当前时间已超过时段结束时间，则该时段不可再挂号。
 *
 * <p>时段窗口（上午 09:00-11:30 / 下午 14:00-18:00）从契约 {@code time_slot_windows} 读取，与
 * {@link AppointmentService} 下单校验共享同一事实源。号源查询出口据此过滤掉已过时段，避免端侧展示
 * 不可约入口、用户提交后才被后端拒绝。未来日期不截止；历史日期由 SQL {@code schedule_date >= today}
 * 兜底；未知时段或 null 安全返回 false（不阻断）。
 *
 * <p>时间经注入的 {@link Clock} 读取，测试可固定时钟覆盖上午/下午截止边界。
 */
@Component
@RequiredArgsConstructor
public class SlotWindowGuard {

    private final Contracts contracts;
    private final Clock clock;

    /** 排班日期 + 时段标签（如"上午"/"下午"，与契约 time_slot_windows 键一致）是否已截止。 */
    public boolean isClosed(LocalDate scheduleDate, String timeSlotValue) {
        if (scheduleDate == null || timeSlotValue == null) {
            return false;
        }
        if (!scheduleDate.equals(LocalDate.now(clock))) {
            return false;
        }
        Contracts.ScheduleRequestFlow.TimeSlotWindow window =
                contracts.scheduleRequestFlow().timeSlotWindows().get(timeSlotValue);
        if (window == null) {
            return false;
        }
        return LocalTime.now(clock).isAfter(LocalTime.parse(window.end()));
    }

    /**
     * 排班实体是否已截止：从 {@link Schedule} 提取日期与时段标签，date/timeSlot 为 null 时安全返回 false。
     * 供 {@link AppointmentService} 下单校验与查询出口复用，避免调用方自行拆字段时漏判 null。
     */
    public boolean isClosed(Schedule schedule) {
        if (schedule.getTimeSlot() == null) {
            return false;
        }
        return isClosed(schedule.getScheduleDate(), schedule.getTimeSlot().getValue());
    }
}
