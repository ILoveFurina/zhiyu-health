package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prescriptions")
public class Prescription {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appointmentId;
    private Long onlineConsultationId;
    private Long doctorId;
    private String status;
    private String notes;
    private String reviewReason;
    private Long reviewedBy;
    private String interpretation;
    private String disclaimer;
    private OffsetDateTime createdAt;
    private OffsetDateTime reviewedAt;

    @TableField(exist = false)
    private String doctorName;

    @TableField(exist = false)
    private String departmentName;

    @TableField(exist = false)
    private String patientNickname;

    @TableField(exist = false)
    private Long patientId;

    @TableField(exist = false)
    private Long healthProfileId;

    /** 双来源 COALESCE 投影：线下取排班日期，在线问诊无排班、取问诊发生日期（绝不伪造排班）。 */
    @TableField(exist = false)
    private java.time.LocalDate scheduleDate;

    /** 来源发生时间投影（COALESCE 挂号单/问诊单 created_at），供统一临床上下文派生。 */
    @TableField(exist = false)
    private OffsetDateTime occurredAt;

    /** 审核队列上下文投影：接诊记录 LEFT JOIN 双外键取诊断/医嘱。 */
    @TableField(exist = false)
    private String diagnosis;

    @TableField(exist = false)
    private String advice;
}
