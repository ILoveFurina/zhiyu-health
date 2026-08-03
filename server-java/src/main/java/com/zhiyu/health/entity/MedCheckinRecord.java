package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 服药打卡生命周期记录：处方审核通过时 eager 预生成每日一条 PENDING，患者点"已服用"后推进 CHECKED。
 * 状态机一致性由 DB CHECK 约束兜底（CHECKED 必须有 checked_at，PENDING 不得有）。
 */
@Getter
@Setter
@TableName("med_checkin_records")
public class MedCheckinRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long healthProfileId;
    private Long prescriptionId;
    private Long prescriptionItemId;
    private String medicationName;
    private String dosage;
    private String frequency;
    private LocalDate dueDate;
    private String status;
    private OffsetDateTime checkedAt;
    private String disclaimer;
    private OffsetDateTime createdAt;
}
