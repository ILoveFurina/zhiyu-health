package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 预问诊草稿实体，镜像 schema.sql preconsultation_drafts 表（票 54：锁定档案的 Agent 预问诊容器） */
@Getter
@Setter
@TableName("preconsultation_drafts")
public class PreconsultationDraft {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long healthProfileId;
    private Long conversationId;
    private String status;
    private String chiefComplaint;
    private String presentIllness;
    private String allergyHistory;
    private String summaryDisclaimer;
    private Long suggestedStandardDepartmentId;
    private OffsetDateTime summaryUpdatedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
