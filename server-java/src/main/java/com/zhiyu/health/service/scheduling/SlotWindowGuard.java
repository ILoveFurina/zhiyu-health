package com.zhiyu.health.service.scheduling;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.scheduling.Schedule;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 排班时段窗口判断（号源硬约束 + 叫号时段窗口，票 87）：
 * 窗口来源收敛为 {@link EffectiveSlotWindows}（演示覆盖优先、契约兜底）。
 *
 * <p>{@code isClosed} 是 C 端挂号截止判断：排班当天且当前时间已超过时段结束时间，则该时段不可再挂号。
 * 未来日期不截止；未知时段或 null 安全返回 false（不阻断挂号）。
 *
 * <p>{@code isWithinWindow} 是 B 端叫号判断：排班当天且当前时间处于 [start, end] 闭区间内才可叫号；
 * 未知时段或 null 一律返回 false（fail-closed），过点滞留的待就诊患者不可叫号。
 *
 * <p>{@code isPastCancelCutoff} 是已支付预约取消截止判断（票 90）：排班当天且当前时间已超过
 * {@code start - cutoffMinutes}，则已支付（BOOKED）预约不可再取消。未来日期不截止（可取消）；
 * 未知时段或 null 安全返回 false（不阻断取消，与 {@code isClosed} 同口径）。
 *
 * <p>时间经注入的 {@link Clock} 读取，测试可固定时钟覆盖上午/下午截止边界。
 */
@Component
@RequiredArgsConstructor
public class SlotWindowGuard {

    private final Clock clock;
    private final EffectiveSlotWindows effectiveSlotWindows;

    /** 排班日期 + 时段标签（如"上午"/"下午"，与契约 time_slot_windows 键一致）是否已截止。 */
    public boolean isClosed(LocalDate scheduleDate, String timeSlotValue) {
        if (scheduleDate == null || timeSlotValue == null) {
            return false;
        }
        if (!scheduleDate.equals(LocalDate.now(clock))) {
            return false;
        }
        Contracts.ScheduleRequestFlow.TimeSlotWindow window =
                effectiveSlotWindows.windows().get(timeSlotValue);
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

    /**
     * 排班日期 + 时段标签是否处于有效时段窗口（闭区间含起止）。未知时段或 null 一律 fail-closed，
     * 保证只有契约或演示覆盖定义了窗口的时段才可叫号。
     */
    public boolean isWithinWindow(LocalDate scheduleDate, String timeSlotValue) {
        if (scheduleDate == null || timeSlotValue == null) {
            return false;
        }
        if (!scheduleDate.equals(LocalDate.now(clock))) {
            return false;
        }
        Contracts.ScheduleRequestFlow.TimeSlotWindow window =
                effectiveSlotWindows.windows().get(timeSlotValue);
        if (window == null) {
            return false;
        }
        LocalTime now = LocalTime.now(clock);
        return !now.isBefore(LocalTime.parse(window.start())) && !now.isAfter(LocalTime.parse(window.end()));
    }

    /**
     * 已支付预约是否已过取消截止时刻（票 90）：排班当天且当前时间已超过 {@code start - cutoffMinutes}。
     * 返回 true 表示该预约已不可取消（距就诊开始不足 cutoffMinutes 分钟）；返回 false 表示仍可取消。
     * 非当天（未来日期）返回 false；未知时段或 null 安全返回 false（不阻断取消，与 {@code isClosed} 同口径）。
     */
    public boolean isPastCancelCutoff(LocalDate scheduleDate, String timeSlotValue, int cutoffMinutes) {
        if (scheduleDate == null || timeSlotValue == null) {
            return false;
        }
        if (!scheduleDate.equals(LocalDate.now(clock))) {
            return false;
        }
        Contracts.ScheduleRequestFlow.TimeSlotWindow window =
                effectiveSlotWindows.windows().get(timeSlotValue);
        if (window == null) {
            return false;
        }
        // 截止时刻 = 时段开始 - cutoffMinutes；当前时间已过该时刻则不可取消。
        // 分钟减法用 minusMinutes 处理跨日回绕（如 cutoff 大于 0 点前的分钟数），结果可能为负值次日时刻，
        // 此时 now.isAfter 必然为 true（当天不会再回到该时刻），语义正确--凌晨后已过昨日截止。
        LocalTime cancelCutoff = LocalTime.parse(window.start()).minusMinutes(cutoffMinutes);
        return LocalTime.now(clock).isAfter(cancelCutoff);
    }

    /**
     * 排班日期 + 时段标签是否已过点（票 94）：供"就诊中"自动转已接诊收敛使用。
     * <p>跨天滞留（schedule_date 早于今天）直接返回 true--不论时段是否已知，过去的就诊中单必须收敛，
     * 彻底释放卡住单叫号约束的滞留单；当天则复用 {@link #isClosed}（now > end，未知时段 fail-open 返回 false，
     * 与挂号截止同口径）；未来日期或 null 返回 false（就诊中不会有未来日期，保险返回 false）。
     */
    public boolean isPast(LocalDate scheduleDate, String timeSlotValue) {
        if (scheduleDate == null) {
            return false;
        }
        if (scheduleDate.isBefore(LocalDate.now(clock))) {
            return true;
        }
        return isClosed(scheduleDate, timeSlotValue);
    }
}
