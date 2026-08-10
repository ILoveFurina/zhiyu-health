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

/**
 * 挂号单实体：映射 appointments 表，承载患者挂号的核心业务数据。
 * <p>
 * 状态机取值由契约 {@code appointment-flow.json} 定义：
 * {@code PENDING_PAYMENT} → {@code BOOKED} → {@code VISITED} / {@code CANCELLED}。
 * 状态流转须经 {@code markCancelled}/{@code markBooked} 的 CAS 守卫，禁止直接 setStatus 裸写。
 * </p>
 */
@Getter
@Setter
@TableName("appointments")
public class Appointment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 患者 ID，外键关联 patients 表。 */
    private Long patientId;

    /**
     * 健康档案 ID，外键关联 health_profiles 表。
     * 唯一索引 {@code uq_appointments_profile_schedule_active} 的组成部分（patient+profile+schedule，排除已取消），
     * 与 {@code selectForProfileAndSchedule} 幂等判重同口径，DB 唯一索引兜底并发重复有效挂号。
     */
    private Long healthProfileId;

    /** 对话会话 ID（AI 导诊链路时非空），外键关联 conversations 表，ON DELETE SET NULL。 */
    private Long conversationId;

    /** 排班 ID，外键关联 schedules 表。 */
    private Long scheduleId;

    /**
     * 就诊序号，由 {@code nextSequenceNumber} 的 MAX+1 生成。
     * 排班行锁（SELECT FOR UPDATE）串行化取号，保证同一排班下不重号。
     */
    private Integer sequenceNumber;

    /** 挂号费金额，创建时从排班关联的医生信息复制。 */
    private BigDecimal registrationFee;

    /**
     * 挂号状态机编码，取值由契约定义：
     * <ul>
     *   <li>{@code PENDING_PAYMENT} — 待支付（挂号成功占位）</li>
     *   <li>{@code BOOKED} — 已支付/待就诊</li>
     *   <li>{@code IN_PROGRESS} — 就诊中</li>
     *   <li>{@code CANCELLED} — 已取消</li>
     *   <li>{@code VISITED} — 已接诊</li>
     * </ul>
     */
    private String status;

    /** 病情摘要（AI 导诊时由会话内容生成），创建后可通过 {@code saveConditionSummary} 补录。 */
    private String conditionSummary;

    /** 记录创建时间，由数据库默认 now() 生成。 */
    private OffsetDateTime createdAt;

    /** 取消时间，cancel 操作时写入。 */
    private OffsetDateTime cancelledAt;

    /**
     * 待支付单的支付截止时刻（票 81）。
     * 支付完成后保留原值作审计痕迹，不随状态推进清空；超时由 {@code expireOverdueAppointments} 惰性收敛。
     */
    private OffsetDateTime paymentDeadline;

    /** 医生 ID（视图投影，联查 schedules → doctors）。 */
    @TableField(exist = false)
    private Long doctorId;

    /** 医生姓名（视图投影）。 */
    @TableField(exist = false)
    private String doctorName;

    /** 科室名称（视图投影，联查 departments）。 */
    @TableField(exist = false)
    private String departmentName;

    /** 就诊日期（视图投影，联查 schedules）。 */
    @TableField(exist = false)
    private LocalDate scheduleDate;

    /** 就诊时段（视图投影，联查 schedules）。 */
    @TableField(exist = false)
    private TimeSlot timeSlot;

    /** 患者昵称（视图投影，联查 patients）。 */
    @TableField(exist = false)
    private String patientNickname;

    /** 患者性别（视图投影，联查 health_profiles，票 94 接诊详情展示）。 */
    @TableField(exist = false)
    private String gender;

    /** 患者出生日期（视图投影，联查 health_profiles，票 94 接诊详情算年龄）。 */
    @TableField(exist = false)
    private LocalDate birthDate;

    /**
     * 收费单状态编码（视图投影：LEFT JOIN payments）。
     * 挂号单可能尚未建支付单（createUnpaid 异步补建），此时为 NULL。
     */
    @TableField(exist = false)
    private String paymentStatus;

    /** 诊室号（视图投影）。 */
    @TableField(exist = false)
    private String room;

    /** 医院名称（视图投影，联查 hospitals）。 */
    @TableField(exist = false)
    private String hospitalName;

    /** 院区名称（视图投影，联查 hospital_campuses）。 */
    @TableField(exist = false)
    private String campusName;

    /** 院区地址（视图投影，联查 hospital_campuses）。 */
    @TableField(exist = false)
    private String campusAddress;
}
