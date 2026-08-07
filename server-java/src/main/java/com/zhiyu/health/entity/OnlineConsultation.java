package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 在线问诊单实体，镜像 schema.sql online_consultations 表（票 54）。
 * 尾部 @TableField(exist = false) 为科室池/详情联查投影：标准科室名、患者昵称与锁定档案信息。
 */
@Getter
@Setter
@TableName("online_consultations")
public class OnlineConsultation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long healthProfileId;
    private Long draftId;
    private Long conversationId;
    private Long standardDepartmentId;
    private Long doctorId;
    private String chiefComplaint;
    private String presentIllness;
    private String allergyHistory;
    private String summaryDisclaimer;
    private String status;
    private String consultMethod;
    private OffsetDateTime methodStartedAt;
    private String diagnosis;
    private String advice;
    private OffsetDateTime expiresAt;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @TableField(exist = false)
    private String standardDepartmentName;

    @TableField(exist = false)
    private String patientNickname;

    @TableField(exist = false)
    private String profileDisplayName;

    @TableField(exist = false)
    private String profileGender;

    @TableField(exist = false)
    private LocalDate profileBirthDate;

    @TableField(exist = false)
    private String profileRelationship;
}
