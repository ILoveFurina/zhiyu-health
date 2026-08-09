package com.zhiyu.health.controller.patient.appointment;

import java.math.BigDecimal;

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
        BigDecimal registrationFee,
        String paymentStatus,
        String paymentStatusLabel,
        String conditionSummary,
        String summaryDisclaimer) {}
