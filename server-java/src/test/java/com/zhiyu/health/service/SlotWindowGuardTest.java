package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiyu.health.service.scheduling.SlotWindowGuard;
import com.zhiyu.health.support.TestContracts;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * 时段截止判断单测（号源硬约束）：固定 Clock 覆盖上午/下午截止边界，避免依赖墙上时钟。
 * 契约时段窗口：上午 09:00-11:30，下午 14:00-18:00。
 */
class SlotWindowGuardTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 固定到 2026-08-08（与 today 断言一致），上午 11:30 与下午 18:00 为截止边界
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    private SlotWindowGuard guardAt(String time) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-08T" + time + ":00+08:00"), ZONE);
        return new SlotWindowGuard(TestContracts.instance(), clock);
    }

    @Test
    void morningIsOpenBeforeEndTime() {
        // 10:00 在上午结束时间 11:30 之前，未截止
        assertThat(guardAt("10:00").isClosed(TODAY, "上午")).isFalse();
    }

    @Test
    void morningIsClosedAfterEndTime() {
        // 12:00 已过上午结束时间 11:30，截止
        assertThat(guardAt("12:00").isClosed(TODAY, "上午")).isTrue();
    }

    @Test
    void afternoonIsOpenBeforeEndTime() {
        // 15:00 在下午结束时间 18:00 之前，未截止
        assertThat(guardAt("15:00").isClosed(TODAY, "下午")).isFalse();
    }

    @Test
    void afternoonIsClosedAfterEndTime() {
        // 18:30 已过下午结束时间 18:00，截止
        assertThat(guardAt("18:30").isClosed(TODAY, "下午")).isTrue();
    }

    @Test
    void futureDateIsNeverClosed() {
        // 未来日期不截止（无论时段与当前时间）
        assertThat(guardAt("12:00").isClosed(TODAY.plusDays(1), "上午")).isFalse();
        assertThat(guardAt("19:00").isClosed(TODAY.plusDays(1), "下午")).isFalse();
    }

    @Test
    void unknownTimeSlotIsNotClosed() {
        // 未知时段无窗口定义，安全返回 false（不阻断）
        assertThat(guardAt("12:00").isClosed(TODAY, "晚上")).isFalse();
        assertThat(guardAt("12:00").isClosed(TODAY, "不存在")).isFalse();
    }

    @Test
    void nullInputsAreSafe() {
        // null 安全：日期或时段为空不阻断
        assertThat(guardAt("12:00").isClosed(null, "上午")).isFalse();
        assertThat(guardAt("12:00").isClosed(TODAY, null)).isFalse();
    }
}
