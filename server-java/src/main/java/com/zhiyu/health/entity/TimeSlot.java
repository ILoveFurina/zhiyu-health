package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum TimeSlot {
    MORNING("上午"),
    AFTERNOON("下午"),
    EVENING("晚上");

    @EnumValue
    private final String value;

    TimeSlot(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TimeSlot fromValue(String value) {
        return Arrays.stream(values())
                .filter(slot -> slot.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的排班时段"));
    }
}
