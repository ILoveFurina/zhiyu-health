package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.service.scheduling.EffectiveSlotWindows;
import com.zhiyu.health.service.scheduling.SlotWindowGuard;
import com.zhiyu.health.support.TestContracts;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 时段窗口单测（号源硬约束 + 叫号时段窗口，票 86）：固定 Clock 覆盖上午/下午边界，
 * 避免依赖墙上时钟。契约时段窗口：上午 09:00-11:30，下午 14:00-18:00（闭区间含起止）。
 */
class SlotWindowGuardTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 固定到 2026-08-08（与 today 断言一致），上午 11:30 与下午 18:00 为截止边界
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    private SlotWindowGuard guardAt(LocalTime time) {
        Clock clock = Clock.fixed(ZonedDateTime.of(TODAY, time, ZONE).toInstant(), ZONE);
        // 测试用有效时段窗口固定回契约（演示开关关闭、不读 Redis）
        EffectiveSlotWindows effective = new EffectiveSlotWindows(
                TestContracts.instance(), mock(StringRedisTemplate.class), new ObjectMapper(), false);
        return new SlotWindowGuard(clock, effective);
    }

    @Test
    void morningIsOpenBeforeEndTime() {
        // 10:00 在上午结束时间 11:30 之前，未截止
        assertThat(guardAt(LocalTime.of(10, 0)).isClosed(TODAY, "上午")).isFalse();
    }

    @Test
    void morningIsClosedAfterEndTime() {
        // 12:00 已过上午结束时间 11:30，截止
        assertThat(guardAt(LocalTime.of(12, 0)).isClosed(TODAY, "上午")).isTrue();
    }

    @Test
    void afternoonIsOpenBeforeEndTime() {
        // 15:00 在下午结束时间 18:00 之前，未截止
        assertThat(guardAt(LocalTime.of(15, 0)).isClosed(TODAY, "下午")).isFalse();
    }

    @Test
    void afternoonIsClosedAfterEndTime() {
        // 18:30 已过下午结束时间 18:00，截止
        assertThat(guardAt(LocalTime.of(18, 30)).isClosed(TODAY, "下午")).isTrue();
    }

    @Test
    void futureDateIsNeverClosed() {
        // 未来日期不截止（无论时段与当前时间）
        assertThat(guardAt(LocalTime.of(12, 0)).isClosed(TODAY.plusDays(1), "上午"))
                .isFalse();
        assertThat(guardAt(LocalTime.of(19, 0)).isClosed(TODAY.plusDays(1), "下午"))
                .isFalse();
    }

    @Test
    void unknownTimeSlotIsNotClosed() {
        // 未知时段无窗口定义，挂号安全返回 false（不阻断）
        assertThat(guardAt(LocalTime.of(12, 0)).isClosed(TODAY, "晚上")).isFalse();
        assertThat(guardAt(LocalTime.of(12, 0)).isClosed(TODAY, "不存在")).isFalse();
    }

    @Test
    void nullInputsAreSafe() {
        // null 安全：日期或时段为空不阻断挂号
        assertThat(guardAt(LocalTime.of(12, 0)).isClosed(null, "上午")).isFalse();
        assertThat(guardAt(LocalTime.of(12, 0)).isClosed(TODAY, null)).isFalse();
    }

    @Test
    void morningWindowIsInclusiveAtStartAndEnd() {
        // 闭区间含起止：09:00:00 与 11:30:00 都可叫号
        assertThat(guardAt(LocalTime.of(9, 0)).isWithinWindow(TODAY, "上午")).isTrue();
        assertThat(guardAt(LocalTime.of(11, 30)).isWithinWindow(TODAY, "上午")).isTrue();
    }

    @Test
    void windowClosedJustAfterEnd() {
        // 11:30:00.001 已过结束时刻，不可叫号
        assertThat(guardAt(LocalTime.of(11, 30, 0, 1_000_000)).isWithinWindow(TODAY, "上午"))
                .isFalse();
    }

    @Test
    void lunchAndAfterHoursAreOutsideWindow() {
        // 午休与下班后不可叫号
        assertThat(guardAt(LocalTime.of(12, 0)).isWithinWindow(TODAY, "上午")).isFalse();
        assertThat(guardAt(LocalTime.of(18, 30)).isWithinWindow(TODAY, "下午")).isFalse();
    }

    @Test
    void afternoonWindowIsInclusiveAtStart() {
        assertThat(guardAt(LocalTime.of(14, 0)).isWithinWindow(TODAY, "下午")).isTrue();
    }

    @Test
    void unknownTimeSlotIsNotCallable() {
        // 未知时段 fail-closed：一律不可叫号
        assertThat(guardAt(LocalTime.of(10, 0)).isWithinWindow(TODAY, "晚上")).isFalse();
        assertThat(guardAt(LocalTime.of(10, 0)).isWithinWindow(TODAY, "不存在")).isFalse();
    }

    @Test
    void nullInputsAreNotCallable() {
        assertThat(guardAt(LocalTime.of(10, 0)).isWithinWindow(null, "上午")).isFalse();
        assertThat(guardAt(LocalTime.of(10, 0)).isWithinWindow(TODAY, null)).isFalse();
    }

    @Test
    void nonTodayDateIsNotCallable() {
        assertThat(guardAt(LocalTime.of(10, 0)).isWithinWindow(TODAY.plusDays(1), "上午"))
                .isFalse();
    }
}
