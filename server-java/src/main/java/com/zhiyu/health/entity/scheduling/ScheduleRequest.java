package com.zhiyu.health.entity.scheduling;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 排班申请实体，镜像 schema.sql schedule_requests 表。
 * 审核通过后回填 schedule_id 关联生成的 schedules 行；status 由契约 schedule-request-flow.json 定义。
 */
@Getter
@Setter
@TableName("schedule_requests")
public class ScheduleRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long doctorId;
    private LocalDate scheduleDate;
    private TimeSlot timeSlot;
    private Integer totalSlots;
    // 申请动作：CREATE（新建排班）/ MODIFY（调整号源）/ DISABLE（停诊）/ ENABLE（复诊），
    // mapper 多处 action IN (...) 依赖此枚举，审核流据此分流落盘逻辑。
    private String action;
    // DISABLE/MODIFY/ENABLE 申请的作用对象（指向既有 schedules 行），与 scheduleId 含义相反；
    // scheduleId 是 CREATE 审核通过后回填的新建排班行 ID。二者绝不同时非空，是最易误用的字段。
    private Long targetScheduleId;
    private String status;
    private Long submittedBy;
    private Long reviewedBy;
    private String reviewReason;
    private Long scheduleId;
    private OffsetDateTime createdAt;
    private OffsetDateTime reviewedAt;

    /** 审核列表联查投影：医生姓名/科室名/职称。 */
    @TableField(exist = false)
    private String doctorName;

    @TableField(exist = false)
    private String departmentName;

    @TableField(exist = false)
    private String title;
}
