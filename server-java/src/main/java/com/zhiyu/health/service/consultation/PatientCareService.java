package com.zhiyu.health.service.consultation;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.health.HealthProfileService;
import com.zhiyu.health.service.prescription.mapping.PrescriptionDtoMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PatientCareService {
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper itemMapper;
    private final InAppMessageMapper messageMapper;
    private final Contracts contracts;
    private final PrescriptionDtoMapper dtoMapper;
    private final HealthProfileService healthProfiles;
    private final ClinicalContextService clinicalContexts;

    public List<PatientPrescriptionView> prescriptions(long patientId) {
        // 患者可见性边界（票 60）：全状态处方对患者可见，确定性边界改为「用药解读只随 APPROVED 出现」——
        // interpretation/disclaimer 仅审核通过时落库（ck_prescriptions_patient_visibility 约束不动），
        // 非 APPROVED 天然为 null，本层不再做状态过滤。
        long profileId = healthProfiles.requireActive(patientId).getId();
        return prescriptionMapper.selectForProfile(patientId, profileId).stream()
                .map(this::toPrescriptionView)
                .toList();
    }

    public List<MessageView> messages(long patientId) {
        return messageMapper.selectForPatient(patientId).stream()
                .map(message -> dtoMapper.toMessageView(
                        message,
                        message.getCreatedAt() == null
                                ? null
                                : message.getCreatedAt().toString()))
                .toList();
    }

    private PatientPrescriptionView toPrescriptionView(Prescription prescription) {
        List<PatientItemView> items = dtoMapper.toPatientItemViews(itemMapper.selectDetailed(prescription.getId()));
        String date = prescription.getScheduleDate() == null
                ? null
                : prescription.getScheduleDate().toString();
        // 来源派生统一走临床上下文模块（数据库不落 source_type 列），取值只经契约（票 56）。
        String sourceType = clinicalContexts.sourceTypeOf(prescription);
        String statusLabel = contracts
                .prescriptionFlow()
                .statusLabels()
                .getOrDefault(prescription.getStatus(), prescription.getStatus());
        // 来源单号供小程序问诊完成页匹配本单处方：appointment/online_consultation 两外键 XOR 必有一值（票 60）。
        Long sourceId = prescription.getAppointmentId() != null
                ? prescription.getAppointmentId()
                : prescription.getOnlineConsultationId();
        return dtoMapper.toPatientPrescriptionView(prescription, sourceType, statusLabel, sourceId, date, items);
    }

    public record PatientItemView(
            Long medicationId,
            String name,
            String specification,
            String dosage,
            String frequency,
            String duration,
            Integer quantity,
            String notes) {}

    public record PatientPrescriptionView(
            Long id,
            String sourceType,
            String status,
            String statusLabel,
            String reviewReason,
            Long sourceId,
            String doctorName,
            String departmentName,
            String date,
            String interpretation,
            String disclaimer,
            List<PatientItemView> items) {}

    public record MessageView(
            Long id, String type, String title, String content, String disclaimer, String createdAt) {}
}
