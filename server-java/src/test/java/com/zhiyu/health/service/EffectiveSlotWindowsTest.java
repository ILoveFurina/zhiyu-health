package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.scheduling.EffectiveSlotWindows;
import com.zhiyu.health.support.TestContracts;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 有效时段窗口（票 86）：演示覆盖优先、契约兜底；env 关闭时忽略 Redis 残留覆盖，
 * 覆盖缺失/损坏/非法一律 fail-safe 回退契约。
 */
class EffectiveSlotWindowsTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final Contracts contracts = TestContracts.instance();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void disabledEnvIgnoresRedisOverride() {
        when(valueOps.get(anyString()))
                .thenReturn(
                        "{\"上午\":{\"start\":\"08:00\",\"end\":\"12:00\"},\"下午\":{\"start\":\"13:00\",\"end\":\"17:00\"}}");

        EffectiveSlotWindows windows = new EffectiveSlotWindows(contracts, redis, mapper, false);

        assertThat(windows.windows()).isEqualTo(contracts.scheduleRequestFlow().timeSlotWindows());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void enabledEnvUsesValidOverride() {
        when(valueOps.get("demo:time_slot_windows"))
                .thenReturn(
                        "{\"上午\":{\"start\":\"08:00\",\"end\":\"12:00\"},\"下午\":{\"start\":\"13:00\",\"end\":\"17:00\"}}");

        EffectiveSlotWindows windows = new EffectiveSlotWindows(contracts, redis, mapper, true);

        Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> effective = windows.windows();
        assertThat(effective.get("上午").start()).isEqualTo("08:00");
        assertThat(effective.get("上午").end()).isEqualTo("12:00");
        assertThat(effective.get("下午").start()).isEqualTo("13:00");
    }

    @Test
    void enabledEnvFallsBackOnMissingOverride() {
        when(valueOps.get("demo:time_slot_windows")).thenReturn(null);

        EffectiveSlotWindows windows = new EffectiveSlotWindows(contracts, redis, mapper, true);

        assertThat(windows.windows()).isEqualTo(contracts.scheduleRequestFlow().timeSlotWindows());
    }

    @Test
    void enabledEnvFallsBackOnCorruptOverride() {
        when(valueOps.get("demo:time_slot_windows")).thenReturn("{not-json");

        EffectiveSlotWindows windows = new EffectiveSlotWindows(contracts, redis, mapper, true);

        assertThat(windows.windows()).isEqualTo(contracts.scheduleRequestFlow().timeSlotWindows());
    }

    @Test
    void enabledEnvFallsBackOnInvalidOverride() {
        // start >= end 的非法覆盖回退契约，不让脏覆盖影响挂号/叫号硬约束
        when(valueOps.get("demo:time_slot_windows"))
                .thenReturn(
                        "{\"上午\":{\"start\":\"12:00\",\"end\":\"11:00\"},\"下午\":{\"start\":\"13:00\",\"end\":\"17:00\"}}");

        EffectiveSlotWindows windows = new EffectiveSlotWindows(contracts, redis, mapper, true);

        assertThat(windows.windows()).isEqualTo(contracts.scheduleRequestFlow().timeSlotWindows());
    }

    @Test
    void isValidRejectsMissingOrInvalidEntries() {
        EffectiveSlotWindows windows = new EffectiveSlotWindows(contracts, redis, mapper, true);

        assertThat(windows.isValid(null)).isFalse();
        assertThat(windows.isValid(Map.of("上午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("09:00", "11:30"))))
                .isFalse();
        assertThat(windows.isValid(Map.of(
                        "上午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("12:00", "11:00"),
                        "下午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("13:00", "17:00"))))
                .isFalse();
        assertThat(windows.isValid(Map.of(
                        "上午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("09:00", "11:30"),
                        "下午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("14:00", "18:00"))))
                .isTrue();
    }
}
