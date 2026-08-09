package com.zhiyu.health.service.consultation;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.entity.consultation.OnlineConsultationMessage;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.health.HealthProfileAllergyMapper;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.ConsultationDetail;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.DoctorConsultationDetail;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.DoctorListItem;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.DoctorView;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.ProfileRef;
import com.zhiyu.health.service.consultation.mapping.OnlineConsultationDtoMapper;

/** 在线问诊两端共享的身份守卫、状态词汇与视图装配；不推进业务状态。 */
final class OnlineConsultationAccess {
    private final OnlineConsultationMapper mapper;
    private final StaffUserMapper staffUsers;
    private final HealthProfileAllergyMapper allergies;
    private final Contracts contracts;
    private final OnlineConsultationDtoMapper dtoMapper;

    OnlineConsultationAccess(
            OnlineConsultationMapper mapper,
            StaffUserMapper staffUsers,
            HealthProfileAllergyMapper allergies,
            Contracts contracts,
            OnlineConsultationDtoMapper dtoMapper) {
        this.mapper = mapper;
        this.staffUsers = staffUsers;
        this.allergies = allergies;
        this.contracts = contracts;
        this.dtoMapper = dtoMapper;
    }

    long requireDoctor(long staffId) {
        StaffUser staff = staffUsers.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole()) || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        return staff.getDoctorId();
    }

    StaffUser staff(long staffId) {
        return staffUsers.selectById(staffId);
    }

    long requireStandardDepartment(long doctorId) {
        Long departmentId = mapper.selectStandardDepartmentIdByDoctor(doctorId);
        if (departmentId == null) {
            throw new ApiException(409, "医生科室未映射标准科室，暂不可接诊在线问诊");
        }
        return departmentId;
    }

    boolean visibleToDoctor(OnlineConsultation consultation, long doctorId) {
        if (consultation.getDoctorId() != null && consultation.getDoctorId() == doctorId) {
            return true;
        }
        if (!waiting().equals(consultation.getStatus())) {
            return false;
        }
        Long departmentId = mapper.selectStandardDepartmentIdByDoctor(doctorId);
        return departmentId != null && departmentId.equals(consultation.getStandardDepartmentId());
    }

    OnlineConsultation requireOwnedByPatient(long id, long patientId) {
        OnlineConsultation consultation = mapper.selectDetailedByIdAndPatient(id, patientId);
        if (consultation == null) {
            // 归属失败与不存在统一 404，避免向越权调用者泄露问诊单存在性。
            throw new ApiException(404, "问诊单不存在");
        }
        return consultation;
    }

    OnlineConsultation requireBoundToDoctor(long id, long doctorId) {
        OnlineConsultation consultation = mapper.selectDetailedById(id);
        if (consultation == null || consultation.getDoctorId() == null || consultation.getDoctorId() != doctorId) {
            throw new ApiException(404, "问诊单不存在");
        }
        return consultation;
    }

    void requireInProgress(OnlineConsultation consultation) {
        if (!inProgress().equals(consultation.getStatus())) {
            throw new ApiException(409, text("not_in_progress"));
        }
    }

    void requireMethodInitiated(OnlineConsultation consultation) {
        if (consultation.getConsultMethod() == null) {
            throw new ApiException(409, text("method_required"));
        }
    }

    void expireOverdue() {
        mapper.expireOverdue(waiting(), expired());
        // 票 86 时长窗惰性收敛与接诊超时同一入口：所有调用点一次调用两种收敛同时生效
        mapper.expireInProgressOverdue(
                inProgress(),
                expired(),
                contracts.onlineConsultation().consultationDurationSeconds(),
                senderType("system"),
                OnlineConsultationMessage.KIND_TEXT,
                text("duration_expired"));
    }

    OnlineConsultation activeByProfile(long healthProfileId) {
        return mapper.selectActiveByProfile(healthProfileId, waiting(), inProgress());
    }

    ConsultationDetail patientDetail(OnlineConsultation consultation) {
        DoctorView doctor = null;
        if (consultation.getDoctorId() != null) {
            OnlineConsultationMapper.DoctorIdentityRow identity =
                    mapper.selectDoctorIdentity(consultation.getDoctorId());
            doctor = identity == null ? null : dtoMapper.toDoctorView(identity);
        }
        String status = consultation.getStatus();
        String progress = contracts.onlineConsultation().isProgressStatus(status) ? status : null;
        return dtoMapper.toDetail(
                consultation,
                dtoMapper.toSummaryView(consultation),
                statusLabel(status),
                progress,
                methodLabel(consultation),
                doctor,
                terminalHint(status),
                consultationEndsAt(consultation));
    }

    DoctorListItem doctorListItem(OnlineConsultation consultation) {
        return dtoMapper.toDoctorListItem(
                consultation,
                dtoMapper.toSummaryView(consultation),
                statusLabel(consultation.getStatus()),
                methodLabel(consultation),
                dtoMapper.toPatientRef(consultation),
                profile(consultation));
    }

    DoctorConsultationDetail doctorDetail(OnlineConsultation consultation) {
        return dtoMapper.toDoctorDetail(
                consultation,
                dtoMapper.toSummaryView(consultation),
                statusLabel(consultation.getStatus()),
                methodLabel(consultation),
                dtoMapper.toPatientRef(consultation),
                profile(consultation),
                consultationEndsAt(consultation));
    }

    /** 双端倒计时截止时间（票 86）：accepted_at + 契约时长窗，仅进行中单有值。 */
    String consultationEndsAt(OnlineConsultation consultation) {
        if (!inProgress().equals(consultation.getStatus()) || consultation.getAcceptedAt() == null) {
            return null;
        }
        return consultation
                .getAcceptedAt()
                .plusSeconds(contracts.onlineConsultation().consultationDurationSeconds())
                .toString();
    }

    private ProfileRef profile(OnlineConsultation consultation) {
        return dtoMapper.toProfileRef(consultation, allergies.selectAllergens(consultation.getHealthProfileId()));
    }

    String systemTextForMethod(String method) {
        return contracts.onlineConsultation().consultMethods().get("video").equals(method)
                ? text("video_started")
                : text("text_started");
    }

    String waiting() {
        return contracts.onlineConsultation().statuses().get("waiting_doctor");
    }

    String inProgress() {
        return contracts.onlineConsultation().statuses().get("in_progress");
    }

    String completed() {
        return contracts.onlineConsultation().statuses().get("completed");
    }

    String cancelled() {
        return contracts.onlineConsultation().statuses().get("cancelled");
    }

    String expired() {
        return contracts.onlineConsultation().statuses().get("expired");
    }

    String senderType(String key) {
        return contracts.onlineConsultation().senderTypes().get(key);
    }

    String statusLabel(String status) {
        return contracts.onlineConsultation().statusLabels().get(status);
    }

    String text(String key) {
        return contracts.onlineConsultation().texts().get(key);
    }

    private String methodLabel(OnlineConsultation consultation) {
        return consultation.getConsultMethod() == null
                ? null
                : contracts.onlineConsultation().consultMethodLabels().get(consultation.getConsultMethod());
    }

    private String terminalHint(String status) {
        if (expired().equals(status)) {
            return text("expired_hint") + text("resubmit_hint");
        }
        if (cancelled().equals(status)) {
            return text("cancelled_hint") + text("resubmit_hint");
        }
        return null;
    }
}
