package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("appointments")
public class Appointment {

    public static final String STATUS_BOOKED = "BOOKED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_VISITED = "VISITED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long conversationId;
    private Long scheduleId;
    private Integer sequenceNumber;
    private String status;
    private String conditionSummary;
    private OffsetDateTime createdAt;
    private OffsetDateTime cancelledAt;

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
}
