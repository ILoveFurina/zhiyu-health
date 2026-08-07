package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.OnlineConsultation;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.OnlineConsultationMapper;
import com.zhiyu.health.mapper.ReceptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 统一临床上下文（票 56，Spec 0003）：从已鉴权的线下挂号单或在线问诊单解析患者、
 * 锁定健康档案、接诊医生、来源与发生时间。开方、禁忌检查、服药提醒、订单归属等
 * 调用方一律经本模块派生上下文，不各写一套来源分支；患者/档案/医生身份只信
 * 服务端已鉴权记录，绝不接受请求体传入。
 */
@Service
@RequiredArgsConstructor
public class ClinicalContextService {

    private final StaffUserMapper staffUserMapper;
    private final ReceptionMapper receptionMapper;
    private final OnlineConsultationMapper onlineConsultationMapper;
    private final Contracts contracts;

    /** 一次可开方临床场景的最小上下文；sourceType 只取契约 prescription-flow source_types 值。 */
    public record ClinicalContext(
            Long patientId, Long healthProfileId, Long doctorId, String sourceType, LocalDateTime occurredAt) {}

    /** 线下挂号开方上下文：医生只能操作自己排班下的挂号单，已取消挂号不可开方。 */
    public ClinicalContext requirePrescribableFromAppointment(long staffId, long appointmentId) {
        long doctorId = requireDoctor(staffId);
        Appointment appointment = receptionMapper.selectAppointment(appointmentId, doctorId);
        if (appointment == null) {
            throw new ApiException(404, "挂号单不存在");
        }
        if (Appointment.STATUS_CANCELLED.equals(appointment.getStatus())) {
            throw new ApiException(409, "已取消挂号不可开方");
        }
        return new ClinicalContext(
                appointment.getPatientId(),
                appointment.getHealthProfileId(),
                doctorId,
                sourceType("appointment"),
                appointment.getCreatedAt() == null
                        ? null
                        : appointment.getCreatedAt().toLocalDateTime());
    }

    /**
     * 在线问诊开方上下文：仅该问诊绑定的医生且 IN_PROGRESS 阶段可开方
     * （归属失败与票 55 既有守卫一致返回 404，不区分原因以免泄露存在性）。
     */
    public ClinicalContext requirePrescribableFromOnlineConsultation(long staffId, long onlineConsultationId) {
        long doctorId = requireDoctor(staffId);
        OnlineConsultation consultation = onlineConsultationMapper.selectDetailedById(onlineConsultationId);
        if (consultation == null || consultation.getDoctorId() == null || consultation.getDoctorId() != doctorId) {
            throw new ApiException(404, "问诊单不存在");
        }
        if (!contracts.onlineConsultation().statuses().get("in_progress").equals(consultation.getStatus())) {
            throw new ApiException(409, contracts.onlineConsultation().texts().get("not_in_progress"));
        }
        // 发生时间取接诊时刻（开方必在 IN_PROGRESS 阶段，问诊可能尚未完成，不取 completed_at）。
        java.time.OffsetDateTime occurredAt =
                consultation.getAcceptedAt() != null ? consultation.getAcceptedAt() : consultation.getCreatedAt();
        return new ClinicalContext(
                consultation.getPatientId(),
                consultation.getHealthProfileId(),
                doctorId,
                sourceType("online_consultation"),
                occurredAt == null ? null : occurredAt.toLocalDateTime());
    }

    /**
     * 从已落库处方派生上下文（服药提醒生成、C 端可见性、订单归属等下游使用）：
     * 患者/档案/发生时间来自 DETAIL_COLUMNS 双来源 COALESCE 投影。
     */
    public ClinicalContext ofPrescription(Prescription prescription) {
        return new ClinicalContext(
                prescription.getPatientId(),
                prescription.getHealthProfileId(),
                prescription.getDoctorId(),
                sourceTypeOf(prescription),
                prescription.getOccurredAt() == null
                        ? null
                        : prescription.getOccurredAt().toLocalDateTime());
    }

    /** 处方来源按非空外键派生（数据库不落 source_type 列），取值只经契约；所有调用方共用本入口。 */
    public String sourceTypeOf(Prescription prescription) {
        return sourceType(prescription.getOnlineConsultationId() != null ? "online_consultation" : "appointment");
    }

    /** B 端医生身份派生（与 ReceptionService 同一模式）：角色与绑定关系只信员工账号记录。 */
    private long requireDoctor(long staffId) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole()) || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        return staff.getDoctorId();
    }

    private String sourceType(String key) {
        return contracts.prescriptionFlow().sourceTypes().get(key);
    }
}
