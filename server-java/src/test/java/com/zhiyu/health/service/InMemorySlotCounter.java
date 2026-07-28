package com.zhiyu.health.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class InMemorySlotCounter implements SlotCounter {

    final Map<Long, AtomicInteger> values = new ConcurrentHashMap<>();

    @Override
    public void initialize(long scheduleId, int remainingSlots) {
        values.put(scheduleId, new AtomicInteger(remainingSlots));
    }

    @Override
    public long decrement(long scheduleId) {
        return values.get(scheduleId).decrementAndGet();
    }

    @Override
    public void increment(long scheduleId) {
        values.get(scheduleId).incrementAndGet();
    }

    @Override
    public void set(long scheduleId, int remainingSlots) {
        values.put(scheduleId, new AtomicInteger(remainingSlots));
    }

    @Override
    public void adjust(long scheduleId, int delta) {
        values.get(scheduleId).addAndGet(delta);
    }

    @Override
    public void delete(long scheduleId) {
        values.remove(scheduleId);
    }
}
