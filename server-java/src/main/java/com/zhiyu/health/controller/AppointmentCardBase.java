package com.zhiyu.health.controller;

import com.zhiyu.health.service.AppointmentService;
import com.zhiyu.health.service.DisclaimerService;

/**
 * AppointmentView → 卡片公共字段转换：C 端 AppointmentOut 与 Agent AppointmentCard 共用，
 * 字段复制与免责声明挂载判断只写一遍（挂载语义已收敛进 DisclaimerService）。
 */
public record AppointmentCardBase(
        Long appointmentId,
        Long scheduleId,
        Long doctorId,
        String doctorName,
        String departmentName,
        String scheduleDate,
        String timeSlot,
        Integer sequenceNumber,
        String status,
        String conditionSummary,
        String summaryDisclaimer) {

    public static AppointmentCardBase from(AppointmentService.AppointmentView value, DisclaimerService disclaimers) {
        return new AppointmentCardBase(
                value.id(),
                value.scheduleId(),
                value.doctorId(),
                value.doctorName(),
                value.departmentName(),
                value.scheduleDate(),
                value.timeSlot(),
                value.sequenceNumber(),
                value.status(),
                value.conditionSummary(),
                // 免责声明挂载判断只留这一处：有摘要才挂载
                disclaimers.mountIfPresent(value.conditionSummary()));
    }
}
