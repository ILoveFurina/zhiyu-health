package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.demo.DemoTimeSlotService;
import com.zhiyu.health.service.scheduling.EffectiveSlotWindows;
import com.zhiyu.health.support.TestContracts;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 演示时段设置（票 86）：env 门控 403、非法窗口 400、合法窗口写 Redis 并回读。 */
class DemoTimeSlotServiceTest {

    private final EffectiveSlotWindows effective = mock(EffectiveSlotWindows.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final Contracts contracts = TestContracts.instance();
    private final ObjectMapper mapper = new ObjectMapper();
    private DemoTimeSlotService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(effective.redisKey()).thenReturn("demo:time_slot_windows");
        service = new DemoTimeSlotService(effective, redis, mapper);
    }

    @Test
    void currentRejectsWhenEnvDisabled() {
        when(effective.timeSlotEnabled()).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class, service::current);

        assertEquals(403, exception.getStatus());
        assertEquals("演示时段设置未开启", exception.getMessage());
    }

    @Test
    void updateRejectsWhenEnvDisabled() {
        when(effective.timeSlotEnabled()).thenReturn(false);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.update(contracts.scheduleRequestFlow().timeSlotWindows()));

        assertEquals(403, exception.getStatus());
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    void updateRejectsInvalidWindow() {
        when(effective.timeSlotEnabled()).thenReturn(true);
        when(effective.isValid(any())).thenReturn(false);
        Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> invalid = Map.of(
                "上午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("12:00", "11:00"),
                "下午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("13:00", "17:00"));

        ApiException exception = assertThrows(ApiException.class, () -> service.update(invalid));

        assertEquals(400, exception.getStatus());
        assertEquals("时段窗口非法：需包含上午/下午且开始时间早于结束时间", exception.getMessage());
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    void updatePersistsAndReturnsView() {
        when(effective.timeSlotEnabled()).thenReturn(true);
        when(effective.isValid(any())).thenReturn(true);
        Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> windows = Map.of(
                "上午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("08:00", "12:00"),
                "下午", new Contracts.ScheduleRequestFlow.TimeSlotWindow("13:00", "17:00"));

        DemoTimeSlotService.TimeSlotWindowView view = service.update(windows);

        assertThat(view.timeSlotWindows()).isEqualTo(windows);
        verify(valueOps).set(eq("demo:time_slot_windows"), anyString());
    }

    @Test
    void currentReturnsEffectiveWindowsWhenEnabled() {
        when(effective.timeSlotEnabled()).thenReturn(true);
        when(effective.windows()).thenReturn(contracts.scheduleRequestFlow().timeSlotWindows());

        DemoTimeSlotService.TimeSlotWindowView view = service.current();

        assertThat(view.timeSlotWindows())
                .isEqualTo(contracts.scheduleRequestFlow().timeSlotWindows());
    }
}
