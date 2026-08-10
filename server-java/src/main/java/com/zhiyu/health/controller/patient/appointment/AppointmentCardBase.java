package com.zhiyu.health.controller.patient.appointment;

import java.math.BigDecimal;

/**
 * 挂号卡片公共字段基座：C 端 {@link AppointmentController.AppointmentOut} 与
 * Agent {@link com.zhiyu.health.controller.agent.AppointmentToolController.AppointmentCard} 共用。
 * <p>
 * 设计意图：将 AppointmentView → 卡片的字段复制逻辑收敛到一处，避免 C 端与 Agent 两端各自维护
 * 相同的字段映射。免责声明挂载语义已收敛进 {@link com.zhiyu.health.service.common.DisclaimerService}，
 * 本 record 只承载纯数据，不做业务判断。
 * </p>
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
