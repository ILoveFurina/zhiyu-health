package com.zhiyu.health.service.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.scheduling.EffectiveSlotWindows;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 演示时段设置（票 86，ADR-0022 模式）：admin 可覆盖上午/下午起止，写 Redis 全局键，
 * C 端挂号截止与 B 端叫号统一走 {@link EffectiveSlotWindows} 的有效时段窗口。
 *
 * <p>env {@code DEMO_TIME_SLOT_ENABLED} 关闭时整个能力不可用（403），生产语义不变；
 * 写入前校验窗口必须包含契约全部时段键且 start &lt; end，非法值 400 拒绝。
 */
@Service
@RequiredArgsConstructor
public class DemoTimeSlotService {

    private final EffectiveSlotWindows effectiveSlotWindows;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 读当前生效时段窗口（演示覆盖优先、契约兜底）；env 未开启 403。 */
    public TimeSlotWindowView current() {
        if (!effectiveSlotWindows.timeSlotEnabled()) {
            throw new ApiException(403, "演示时段设置未开启");
        }
        return new TimeSlotWindowView(effectiveSlotWindows.windows());
    }

    /** 写演示时段覆盖并回读同值；env 未开启 403，非法窗口 400。 */
    public TimeSlotWindowView update(Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> windows) {
        if (!effectiveSlotWindows.timeSlotEnabled()) {
            throw new ApiException(403, "演示时段设置未开启");
        }
        if (!effectiveSlotWindows.isValid(windows)) {
            throw new ApiException(400, "时段窗口非法：需包含上午/下午且开始时间早于结束时间");
        }
        try {
            redis.opsForValue().set(effectiveSlotWindows.redisKey(), objectMapper.writeValueAsString(windows));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("演示时段设置序列化失败", e);
        }
        return new TimeSlotWindowView(windows);
    }

    public record TimeSlotWindowView(Map<String, Contracts.ScheduleRequestFlow.TimeSlotWindow> timeSlotWindows) {}
}
