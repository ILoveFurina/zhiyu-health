package com.zhiyu.health.service.scheduling;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 号源 Redis 计数器：SlotCounter 的唯一实现，由 SlotAccounting 独占调用（ArchUnit 强制）。
 * 原子操作直接映射 Redis DECR/INCR，不在此处做业务校验--售罄判负与回补补偿由 SlotAccounting 统一裁决。
 */
@Component
@RequiredArgsConstructor
public class RedisSlotCounter implements SlotCounter {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void initialize(long scheduleId, int remainingSlots) {
        set(scheduleId, remainingSlots);
    }

    @Override
    public long decrement(long scheduleId) {
        // Redis DECR 对不存在的键会自创为 0 再减成负数；调用前必须经 initialize 初始化计数，
        // 否则未初始化扣减会产生负数计数（由 SlotAccounting 判负后回补，但属异常路径）。
        Long remaining = redisTemplate.opsForValue().decrement(key(scheduleId));
        if (remaining == null) {
            throw new IllegalStateException("Redis 号源扣减未返回结果");
        }
        return remaining;
    }

    @Override
    public void increment(long scheduleId) {
        redisTemplate.opsForValue().increment(key(scheduleId));
    }

    @Override
    public void set(long scheduleId, int remainingSlots) {
        redisTemplate.opsForValue().set(key(scheduleId), Integer.toString(remainingSlots));
    }

    @Override
    public void adjust(long scheduleId, int delta) {
        redisTemplate.opsForValue().increment(key(scheduleId), delta);
    }

    @Override
    public void delete(long scheduleId) {
        redisTemplate.delete(key(scheduleId));
    }

    static String key(long scheduleId) {
        return SlotKeys.key(scheduleId);
    }
}
