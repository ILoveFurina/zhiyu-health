package com.zhiyu.health.service.consultation;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.consultation.ConsultationRecord;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.entity.consultation.OnlineConsultationMessage;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.consultation.ConsultationRecordMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.ConsultationPrescriptionView;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.DoctorConsultationDetail;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.DoctorListItem;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.MessageView;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/** 医生工作台流程：科室池、并发接单、接诊方式、问诊消息、完成及完成后关怀。 */
final class DoctorOnlineConsultationWorkflow {
    private static final Logger log = LoggerFactory.getLogger(DoctorOnlineConsultationWorkflow.class);

    private final OnlineConsultationMapper mapper;
    private final ConsultationRecordMapper records;
    private final TransactionTemplate transactions;
    private final Contracts contracts;
    private final OnlineConsultationMessaging messaging;
    private final PrescriptionMapper prescriptions;
    private final InAppMessageMapper inAppMessages;
    private final DisclaimerService disclaimers;
    private final OnlineConsultationAccess access;
    private boolean followUpVisibleImmediately;

    DoctorOnlineConsultationWorkflow(
            OnlineConsultationMapper mapper,
            ConsultationRecordMapper records,
            TransactionTemplate transactions,
            Contracts contracts,
            OnlineConsultationMessaging messaging,
            PrescriptionMapper prescriptions,
            InAppMessageMapper inAppMessages,
            DisclaimerService disclaimers,
            OnlineConsultationAccess access) {
        this.mapper = mapper;
        this.records = records;
        this.transactions = transactions;
        this.contracts = contracts;
        this.messaging = messaging;
        this.prescriptions = prescriptions;
        this.inAppMessages = inAppMessages;
        this.disclaimers = disclaimers;
        this.access = access;
    }

    List<DoctorListItem> pool(long staffId) {
        long doctorId = access.requireDoctor(staffId);
        long departmentId = access.requireStandardDepartment(doctorId);
        access.expireOverdue();
        return mapper.selectPool(departmentId, access.waiting()).stream()
                .map(access::doctorListItem)
                .toList();
    }

    List<DoctorListItem> mine(long staffId, String status) {
        long doctorId = access.requireDoctor(staffId);
        String filter = null;
        if (status != null && !status.isBlank()) {
            if (!access.inProgress().equals(status) && !access.completed().equals(status)) {
                throw new ApiException(400, "status 仅支持 IN_PROGRESS/COMPLETED");
            }
            filter = status;
        }
        access.expireOverdue();
        return mapper.selectMine(doctorId, filter).stream()
                .map(access::doctorListItem)
                .toList();
    }

    DoctorConsultationDetail detail(long staffId, long id) {
        long doctorId = access.requireDoctor(staffId);
        access.expireOverdue();
        OnlineConsultation consultation = mapper.selectDetailedById(id);
        if (consultation == null || !access.visibleToDoctor(consultation, doctorId)) {
            throw new ApiException(404, "问诊单不存在");
        }
        return access.doctorDetail(consultation);
    }

    /** 条件 UPDATE 同时校验科室可见性后的状态、未绑定和未过期；并发接单只有一方成功。 */
    DoctorConsultationDetail accept(long staffId, long id) {
        long doctorId = access.requireDoctor(staffId);
        OnlineConsultation consultation = mapper.selectDetailedById(id);
        if (consultation == null || !access.visibleToDoctor(consultation, doctorId)) {
            throw new ApiException(404, "问诊单不存在");
        }
        return transactions.execute(status -> {
            access.expireOverdue();
            if (mapper.accept(id, doctorId, access.waiting(), access.inProgress()) != 1) {
                throw new ApiException(409, access.text("accept_conflict"));
            }
            messaging.append(
                    id,
                    access.senderType("system"),
                    OnlineConsultationMessage.KIND_TEXT,
                    access.text("doctor_accepted"));
            logDecision("accept", id, doctorId);
            return access.doctorDetail(mapper.selectDetailedById(id));
        });
    }

    DoctorConsultationDetail startMethod(long staffId, long id, String method) {
        if (!contracts.onlineConsultation().isKnownConsultMethod(method)) {
            throw new ApiException(400, "接诊方式不合法");
        }
        long doctorId = access.requireDoctor(staffId);
        // 时长窗惰性收敛（票 86）：先翻 EXPIRED 再做状态守卫，医生不能对已到期的问诊继续操作
        access.expireOverdue();
        OnlineConsultation consultation = access.requireBoundToDoctor(id, doctorId);
        access.requireInProgress(consultation);
        if (consultation.getConsultMethod() != null) {
            if (consultation.getConsultMethod().equals(method)) {
                return access.doctorDetail(consultation);
            }
            throw new ApiException(409, access.text("method_already_set"));
        }
        return transactions.execute(status -> {
            if (mapper.startMethod(id, doctorId, access.inProgress(), method) != 1) {
                OnlineConsultation raced = mapper.selectDetailedById(id);
                if (raced.getConsultMethod() != null && raced.getConsultMethod().equals(method)) {
                    return access.doctorDetail(raced);
                }
                throw new ApiException(409, access.text("method_already_set"));
            }
            messaging.append(
                    id,
                    access.senderType("system"),
                    OnlineConsultationMessage.KIND_TEXT,
                    access.systemTextForMethod(method));
            return access.doctorDetail(mapper.selectDetailedById(id));
        });
    }

    List<MessageView> listMessages(long staffId, long id, long afterId) {
        long doctorId = access.requireDoctor(staffId);
        access.expireOverdue();
        access.requireBoundToDoctor(id, doctorId);
        return messaging.list(id, afterId);
    }

    MessageView sendMessage(long staffId, long id, String content) {
        long doctorId = access.requireDoctor(staffId);
        access.expireOverdue();
        OnlineConsultation consultation = access.requireBoundToDoctor(id, doctorId);
        access.requireInProgress(consultation);
        access.requireMethodInitiated(consultation);
        return messaging.append(id, access.senderType("doctor"), OnlineConsultationMessage.KIND_TEXT, content);
    }

    /** 完成状态、接诊记录、系统消息与关怀消息在同一事务提交，任何一步失败都整体回滚。 */
    DoctorConsultationDetail complete(long staffId, long id, String diagnosis, String advice) {
        long doctorId = access.requireDoctor(staffId);
        // 时长窗惰性收敛（票 86）：已到期的进行中单先翻 EXPIRED，不可再完成
        access.expireOverdue();
        OnlineConsultation consultation = access.requireBoundToDoctor(id, doctorId);
        if (access.completed().equals(consultation.getStatus())) {
            return access.doctorDetail(consultation);
        }
        access.requireInProgress(consultation);
        return transactions.execute(status -> {
            if (mapper.complete(id, doctorId, access.inProgress(), access.completed()) != 1) {
                throw new ApiException(409, access.text("not_in_progress"));
            }
            ConsultationRecord record = new ConsultationRecord();
            record.setOnlineConsultationId(id);
            record.setDoctorId(doctorId);
            record.setDiagnosis(diagnosis.trim());
            record.setAdvice(advice.trim());
            records.insert(record);
            writeFollowUp(consultation);
            messaging.append(
                    id,
                    access.senderType("system"),
                    OnlineConsultationMessage.KIND_TEXT,
                    access.text("consult_completed"));
            logDecision("complete", id, doctorId);
            return access.doctorDetail(mapper.selectDetailedById(id));
        });
    }

    ConsultationPrescriptionView prescription(long staffId, long id) {
        StaffUser staff = access.staff(staffId);
        if (staff != null && StaffUser.ROLE_ADMIN.equals(staff.getRole())) {
            if (mapper.selectDetailedById(id) == null) {
                throw new ApiException(404, "问诊单不存在");
            }
        } else if (staff != null && StaffUser.ROLE_DOCTOR.equals(staff.getRole()) && staff.getDoctorId() != null) {
            access.requireBoundToDoctor(id, staff.getDoctorId());
        } else {
            throw new ApiException(403, "仅医生或管理员可操作");
        }
        Prescription prescription = prescriptions.selectByOnlineConsultationId(id);
        if (prescription == null) {
            return null;
        }
        String label = contracts
                .prescriptionFlow()
                .statusLabels()
                .getOrDefault(prescription.getStatus(), prescription.getStatus());
        return new ConsultationPrescriptionView(
                prescription.getId(), prescription.getStatus(), label, prescription.getReviewReason());
    }

    void setFollowUpVisibleImmediately(boolean enabled) {
        this.followUpVisibleImmediately = enabled;
    }

    /** 唯一约束让完成重试不会重复投递；演示开关只改变可见时间，不改变消息内容。 */
    private void writeFollowUp(OnlineConsultation consultation) {
        Contracts.OnlineConsultation.FollowUp followUp =
                contracts.onlineConsultation().followUp();
        InAppMessage message = new InAppMessage();
        message.setPatientId(consultation.getPatientId());
        message.setType(followUp.messageType());
        message.setTitle(followUp.title());
        message.setContent(followUp.content());
        message.setDisclaimer(disclaimers.text());
        message.setRelatedOnlineConsultationId(consultation.getId());
        if (!followUpVisibleImmediately) {
            message.setVisibleAt(OffsetDateTime.now().plusDays(followUp.delayDays()));
        }
        inAppMessages.insertIgnoreConflict(message);
    }

    private void logDecision(String decision, long consultationId, long doctorId) {
        log.info(
                "online consultation decision={} consultationId={} doctorId={}",
                contracts.onlineConsultation().decisions().get(decision),
                consultationId,
                doctorId);
    }
}
