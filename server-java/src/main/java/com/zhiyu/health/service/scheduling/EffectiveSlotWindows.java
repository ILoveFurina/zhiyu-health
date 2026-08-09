package com.zhiyu.health.service.scheduling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.Contracts;
import java.time.LocalTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 有效时段窗口（票 86）：演示覆盖优先、契约兜底，C 端挂号截止与 B 端叫号共享同一事实源。
 *
 * <p>当演示开关 {@code DEMO_TIME_SLOT_ENABLED} 关闭时忽略 Redis 残留覆盖，恒返回契约窗口，
 * 生产语义不变；开启后读 Redis 键 {@code demo:time_slot_windows}，缺失或损坏时 fail-safe 回退契约，
 * 不让脏覆盖影响挂号/叫号硬约束。
 */
@Component
@Slf4j
public class EffectiveSlotWindows {

    private final Contracts contracts;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean timeSlotEnabled;

    public EffectiveSlotWindows(
            Contracts contracts,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${zhiyu.demo.time-slot-enabled:false}") boolean timeSlotEnabled) {
        this.contracts = contracts;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.timeSlotEnabled = timeSlotEnabled;
    }

    /** 演示时段覆盖是否开启（/api/b/demo/time-slot-windows 的 admin 门控）。 */
    public boolean timeSlotEnabled() {
        return timeSlotEnabled;
    }

    /** 演示时段覆盖 Redis 键（契约单一事实源）。 */
    public String redisKey() {
        return contracts.demoArsenal().timeSlotWindowsRedisKey();
    }

    /**
     * 当前生效时段窗口：演示开启且 Redis 有合法覆盖时用覆盖，否则契约窗口。
     * 解析或校验失败一律回退契约（fail-safe），避免损坏的覆盖阻断挂号/叫号。
     */
    public Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> windows() {
        if (!timeSlotEnabled) {
            return contracts.scheduleRequestFlow().timeSlotWindows();
        }
        String json = redis.opsForValue().get(redisKey());
        if (json == null || json.isBlank()) {
            return contracts.scheduleRequestFlow().timeSlotWindows();
        }
        try {
            Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> override =
                    objectMapper.readValue(json, new TypeReference<>() {});
            if (isValid(override)) {
                return Map.copyOf(override);
            }
            log.warn("demo time-slot-windows 覆盖非法，回退契约窗口");
        } catch (Exception e) {
            log.warn("demo time-slot-windows 解析失败，回退契约窗口: {}", e.getMessage());
        }
        return contracts.scheduleRequestFlow().timeSlotWindows();
    }

    /**
     * 覆盖必须恰好包含契约全部时段键（上午/下午），且每段 start/end 可解析为 LocalTime 且 start < end。
     * 写前与读回退共用同一校验，保证落库值与生效值口径一致。
     */
    public boolean isValid(Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> windows) {
        if (windows == null) {
            return false;
        }
        var expectedKeys = contracts.scheduleRequestFlow().timeSlots().values();
        if (!windows.keySet().containsAll(expectedKeys) || !expectedKeys.containsAll(windows.keySet())) {
            return false;
        }
        for (Contracts.ScheduleRequestFlow.TimeSlotWindow window : windows.values()) {
            if (window == null || window.start() == null || window.end() == null) {
                return false;
            }
            try {
                if (!LocalTime.parse(window.start()).isBefore(LocalTime.parse(window.end()))) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }
}
