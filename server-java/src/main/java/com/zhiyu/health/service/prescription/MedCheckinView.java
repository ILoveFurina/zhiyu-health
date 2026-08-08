package com.zhiyu.health.service.prescription;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/**
 * 服药打卡视图：C 端消息页聚合 PENDING 提醒与打卡接口返回 CHECKED 结果共用此形状。
 * streak（连续天数）仅在打卡成功响应中携带，列表接口返回 null。
 */
public record MedCheckinView(
        Long id,
        @JsonProperty("prescription_id") Long prescriptionId,
        @JsonProperty("medication_name") String medicationName,
        String dosage,
        String frequency,
        @JsonProperty("due_date") LocalDate dueDate,
        String status,
        @JsonProperty("checked_at") String checkedAt,
        String disclaimer,
        Integer streak) {}
