package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prescriptions")
public class Prescription {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final Map<String, String> STATUS_DISPLAY =
            Map.of(STATUS_PENDING, "待审核", STATUS_APPROVED, "已通过", STATUS_REJECTED, "已驳回");

    public static String displayStatus(String status) {
        return STATUS_DISPLAY.getOrDefault(status, status);
    }

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appointmentId;
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
    private java.time.LocalDate scheduleDate;
}
