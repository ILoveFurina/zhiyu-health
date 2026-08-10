package com.zhiyu.health.service.consultation;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.consultation.ReceptionMapper;
import com.zhiyu.health.mapper.organization.DoctorMapper;
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
    private final DoctorMapper doctorMapper;
    private final Contracts contracts;

    /** 一次可开方临床场景的最小上下文；sourceType 只取契约 prescription-flow source_types 值。 */
    public record ClinicalContext(
            Long patientId,
            Long healthProfileId,
            Long doctorId,
            String sourceType,
            LocalDateTime occurredAt,
            Long sourceCampusId) {}

    /** 已鉴权医生的身份与当前所属院区（票 88：开方目录与来源院区固化的唯一派生点）。 */
    public record DoctorContext(long doctorId, long campusId) {}

    /** B 端医生身份派生（与 ReceptionService 同一模式）：角色与绑定关系只信员工账号记录。 */
    public DoctorContext requireDoctorContext(long staffId) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole()) || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        // 来源院区从医生当时所属科室派生并固化到处方，禁止客户端传入或后续跟随医生调动。
        Long campusId = doctorMapper.selectCampusIdByDoctorId(staff.getDoctorId());
        if (campusId == null) {
            throw new ApiException(409, "医生所属科室未配置院区");
        }
        return new DoctorContext(staff.getDoctorId(), campusId);
    }

    /** 线下挂号开方上下文：医生只能操作自己排班下的挂号单，已取消挂号不可开方。 */
    public ClinicalContext requirePrescribableFromAppointment(long staffId, long appointmentId) {
        DoctorContext doctor = requireDoctorContext(staffId);
        Appointment appointment = receptionMapper.selectAppointment(appointmentId, doctor.doctorId());
        if (appointment == null) {
            throw new ApiException(404, "挂号单不存在");
        }
        if (contracts.appointmentFlow().status("cancelled").equals(appointment.getStatus())) {
            throw new ApiException(409, "已取消挂号不可开方");
        }
        // 已接诊为只读终态：开方必须在接诊完成前完成，完成后禁止再开方
        // （与在线问诊强制 IN_PROGRESS 的语义对齐，票 93）。
        if (contracts.appointmentFlow().status("visited").equals(appointment.getStatus())) {
            throw new ApiException(409, "已接诊挂号不可开方");
        }
        return new ClinicalContext(
                appointment.getPatientId(),
                appointment.getHealthProfileId(),
                doctor.doctorId(),
                sourceType("appointment"),
                appointment.getCreatedAt() == null
                        ? null
                        : appointment.getCreatedAt().toLocalDateTime(),
                doctor.campusId());
    }

    /**
     * 在线问诊开方上下文：仅该问诊绑定的医生且 IN_PROGRESS 阶段可开方
     * （归属失败与票 55 既有守卫一致返回 404，不区分原因以免泄露存在性）。
     */
    public ClinicalContext requirePrescribableFromOnlineConsultation(long staffId, long onlineConsultationId) {
        DoctorContext doctor = requireDoctorContext(staffId);
        OnlineConsultation consultation = onlineConsultationMapper.selectDetailedById(onlineConsultationId);
        if (consultation == null
                || consultation.getDoctorId() == null
                || consultation.getDoctorId() != doctor.doctorId()) {
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
                doctor.doctorId(),
                sourceType("online_consultation"),
                occurredAt == null ? null : occurredAt.toLocalDateTime(),
                doctor.campusId());
    }

    /**
     * 从已落库处方派生上下文（服药提醒生成、C 端可见性、订单归属等下游使用）：
     * 患者/档案/发生时间来自 DETAIL_COLUMNS 双来源 COALESCE 投影；来源院区取处方
     * 开方时固化的不可变列（票 88），不再从医生现所属科室现查。
     */
    public ClinicalContext ofPrescription(Prescription prescription) {
        return new ClinicalContext(
                prescription.getPatientId(),
                prescription.getHealthProfileId(),
                prescription.getDoctorId(),
                sourceTypeOf(prescription),
                prescription.getOccurredAt() == null
                        ? null
                        : prescription.getOccurredAt().toLocalDateTime(),
                prescription.getSourceCampusId());
    }

    /** 处方来源按非空外键派生（数据库不落 source_type 列），取值只经契约；所有调用方共用本入口。 */
    public String sourceTypeOf(Prescription prescription) {
        return sourceType(prescription.getOnlineConsultationId() != null ? "online_consultation" : "appointment");
    }

    private String sourceType(String key) {
        return contracts.prescriptionFlow().sourceTypes().get(key);
    }
}
