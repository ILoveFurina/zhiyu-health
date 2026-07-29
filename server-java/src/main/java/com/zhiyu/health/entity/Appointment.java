package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("appointments")
public class Appointment {

    public static final String STATUS_BOOKED = "BOOKED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_VISITED = "VISITED";

    private static final Map<String, String> STATUS_DISPLAY = Map.of(
            STATUS_BOOKED, "已约",
            STATUS_CANCELLED, "已取消",
            STATUS_VISITED, "已接诊");

    /** 状态码 → 中文展示；未知值按已约兜底（对齐 Python 原件 switch 默认分支）。 */
    public static String displayStatus(String status) {
        return STATUS_DISPLAY.getOrDefault(status, STATUS_DISPLAY.get(STATUS_BOOKED));
    }

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
