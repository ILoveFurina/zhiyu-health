package com.zhiyu.health.entity.appointment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("appointments")
public class Appointment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long healthProfileId;
    private Long conversationId;
    private Long scheduleId;
    private Integer sequenceNumber;
    private BigDecimal registrationFee;
    private String status;
    private String conditionSummary;
    private OffsetDateTime createdAt;
    private OffsetDateTime cancelledAt;
    // 待支付单的支付截止时刻；支付完成后保留原值作审计痕迹，不随状态推进清空（票 81）。
    private OffsetDateTime paymentDeadline;

    @TableField(exist = false)
    private Long doctorId;

    @TableField(exist = false)
    private String doctorName;

    @TableField(exist = false)
    private String departmentName;

    @TableField(exist = false)
    private LocalDate scheduleDate;

    @TableField(exist = false)
    private TimeSlot timeSlot;

    @TableField(exist = false)
    private String patientNickname;

    @TableField(exist = false)
    private String paymentStatus;

    @TableField(exist = false)
    private String room;

    @TableField(exist = false)
    private String hospitalName;

    @TableField(exist = false)
    private String campusName;

    @TableField(exist = false)
    private String campusAddress;
}
