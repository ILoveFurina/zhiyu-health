package com.zhiyu.health.service.scheduling;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
