package com.zhiyu.health.service.scheduling;

public interface SlotCounter {

    void initialize(long scheduleId, int remainingSlots);

    long decrement(long scheduleId);

    void increment(long scheduleId);

    void set(long scheduleId, int remainingSlots);

    void adjust(long scheduleId, int delta);

    void delete(long scheduleId);
}
