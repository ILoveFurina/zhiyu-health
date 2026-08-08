package com.zhiyu.health.service.consultation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.mapper.chat.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.ConsultationRecordMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMessageMapper;
import com.zhiyu.health.mapper.health.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.ConsultationDetail;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.ConsultationListItem;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.ConsultationPrescriptionView;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.DoctorConsultationDetail;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.DoctorListItem;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.MessageView;
import com.zhiyu.health.service.consultation.mapping.OnlineConsultationDtoMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 在线问诊稳定门面。患者生命周期与医生工作台分别由两个 workflow 维护，共享的身份/状态守卫集中在
 * {@link OnlineConsultationAccess}，消息与图片旁路集中在 {@link OnlineConsultationMessaging}。
 */
@Service
public class OnlineConsultationService {
    private final PatientOnlineConsultationWorkflow patient;
    private final DoctorOnlineConsultationWorkflow doctor;

    // 保留在门面便于演示配置与既有测试注入；仅在完成问诊前同步给医生流程。
    @Value("${zhiyu.demo.follow-up-visible-immediately:false}")
    private boolean followUpVisibleImmediately;

    public OnlineConsultationService(
            OnlineConsultationMapper consultationMapper,
            OnlineConsultationMessageMapper messageMapper,
            PreconsultationDraftMapper draftMapper,
            StaffUserMapper staffUserMapper,
            HealthProfileAllergyMapper allergyMapper,
            ConsultationRecordMapper consultationRecordMapper,
            TransactionTemplate transactionTemplate,
            Contracts contracts,
            OnlineConsultationDtoMapper dtoMapper,
            MinioStorageService minioStorage,
            ObjectMapper objectMapper,
            PrescriptionMapper prescriptionMapper,
            InAppMessageMapper inAppMessageMapper,
            DisclaimerService disclaimers) {
        OnlineConsultationMessaging messaging =
                new OnlineConsultationMessaging(messageMapper, dtoMapper, contracts, minioStorage, objectMapper);
        OnlineConsultationAccess access =
                new OnlineConsultationAccess(consultationMapper, staffUserMapper, allergyMapper, contracts, dtoMapper);
        this.patient = new PatientOnlineConsultationWorkflow(
                consultationMapper, draftMapper, transactionTemplate, contracts, dtoMapper, messaging, access);
        this.doctor = new DoctorOnlineConsultationWorkflow(
                consultationMapper,
                consultationRecordMapper,
                transactionTemplate,
                contracts,
                messaging,
                prescriptionMapper,
                inAppMessageMapper,
                disclaimers,
                access);
    }

    public ConsultationDetail confirm(long patientId, long draftId) {
        return patient.confirm(patientId, draftId);
    }

    public List<ConsultationListItem> listMine(long patientId) {
        return patient.listMine(patientId);
    }

    public ConsultationDetail detail(long patientId, long id) {
        return patient.detail(patientId, id);
    }

    public ConsultationDetail cancel(long patientId, long id) {
        return patient.cancel(patientId, id);
    }

    public ConsultationDetail resubmit(long patientId, long id) {
        return patient.resubmit(patientId, id);
    }

    public List<MessageView> listMessagesForPatient(long patientId, long id, long afterId) {
        return patient.listMessages(patientId, id, afterId);
    }

    public MessageView sendMessageForPatient(long patientId, long id, String content) {
        return patient.sendMessage(patientId, id, content);
    }

    public MessageView sendImageForPatient(long patientId, long id, MultipartFile file) {
        return patient.sendImage(patientId, id, file);
    }

    public List<DoctorListItem> pool(long staffId) {
        return doctor.pool(staffId);
    }

    public List<DoctorListItem> mine(long staffId, String status) {
        return doctor.mine(staffId, status);
    }

    public DoctorConsultationDetail detailForDoctor(long staffId, long id) {
        return doctor.detail(staffId, id);
    }

    public DoctorConsultationDetail accept(long staffId, long id) {
        return doctor.accept(staffId, id);
    }

    public DoctorConsultationDetail startMethod(long staffId, long id, String method) {
        return doctor.startMethod(staffId, id, method);
    }

    public List<MessageView> listMessagesForDoctor(long staffId, long id, long afterId) {
        return doctor.listMessages(staffId, id, afterId);
    }

    public MessageView sendMessageForDoctor(long staffId, long id, String content) {
        return doctor.sendMessage(staffId, id, content);
    }

    public DoctorConsultationDetail complete(long staffId, long id, String diagnosis, String advice) {
        doctor.setFollowUpVisibleImmediately(followUpVisibleImmediately);
        return doctor.complete(staffId, id, diagnosis, advice);
    }

    public ConsultationPrescriptionView prescriptionForConsultation(long staffId, long id) {
        return doctor.prescription(staffId, id);
    }
}
