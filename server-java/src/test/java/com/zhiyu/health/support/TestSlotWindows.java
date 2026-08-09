package com.zhiyu.health.support;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.service.scheduling.EffectiveSlotWindows;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 测试用有效时段窗口助手：演示开关关闭，恒回契约窗口（不读 Redis）。 */
public final class TestSlotWindows {

    private TestSlotWindows() {}

    public static EffectiveSlotWindows contractOnly() {
        return new EffectiveSlotWindows(
                TestContracts.instance(), mock(StringRedisTemplate.class), new ObjectMapper(), false);
    }
}
