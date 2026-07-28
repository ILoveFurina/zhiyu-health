package com.zhiyu.health.service;

public interface SlotCounter {

    void initialize(long scheduleId, int remainingSlots);

    long decrement(long scheduleId);

    void increment(long scheduleId);

    void set(long scheduleId, int remainingSlots);

    void delete(long scheduleId);
}
