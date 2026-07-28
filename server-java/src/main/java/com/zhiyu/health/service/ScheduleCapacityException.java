package com.zhiyu.health.service;

public class ScheduleCapacityException extends RuntimeException {

    public ScheduleCapacityException() {
        super("号源总数不能小于已使用号源数");
    }
}
