package com.zhiyu.health.service.consultation.mapping;

import com.zhiyu.health.entity.chat.PreconsultationDraft;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.service.consultation.PatientConsultationProgressService.ProgressItem;
import java.time.OffsetDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 首页在线问诊进度的跨来源 DTO 映射。 */
@Mapper(componentModel = "spring")
public interface PatientConsultationProgressDtoMapper {

    @Mapping(target = "referenceType", constant = "DRAFT")
    @Mapping(target = "referenceId", source = "draft.id")
    @Mapping(target = "status", source = "draft.status")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "healthProfileId", source = "draft.healthProfileId")
    @Mapping(target = "healthProfileName", source = "healthProfileName")
    @Mapping(target = "onlineConsultationId", ignore = true)
    @Mapping(target = "prescriptionId", ignore = true)
    @Mapping(target = "doctorName", ignore = true)
    @Mapping(target = "departmentName", ignore = true)
    @Mapping(target = "consultationEndsAt", ignore = true)
    @Mapping(target = "updatedAt", source = "draft.updatedAt")
    ProgressItem fromDraft(PreconsultationDraft draft, String statusLabel, String healthProfileName);

    @Mapping(target = "referenceType", constant = "CONSULTATION")
    @Mapping(target = "referenceId", source = "consultation.id")
    @Mapping(target = "status", source = "consultation.status")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "healthProfileId", source = "consultation.healthProfileId")
    @Mapping(target = "healthProfileName", source = "healthProfileName")
    @Mapping(target = "onlineConsultationId", source = "consultation.id")
    @Mapping(target = "prescriptionId", ignore = true)
    @Mapping(target = "doctorName", ignore = true)
    @Mapping(target = "departmentName", ignore = true)
    @Mapping(target = "consultationEndsAt", source = "consultationEndsAt")
    @Mapping(target = "updatedAt", source = "consultation.updatedAt")
    ProgressItem fromConsultation(
            OnlineConsultation consultation, String statusLabel, String healthProfileName, String consultationEndsAt);

    // 票 86 处方追踪：referenceType=PRESCRIPTION，reference_id 与 prescription_id 同为处方 ID
    @Mapping(target = "referenceType", constant = "PRESCRIPTION")
    @Mapping(target = "referenceId", source = "row.prescriptionId")
    @Mapping(target = "status", source = "row.prescriptionStatus")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "healthProfileId", source = "row.healthProfileId")
    @Mapping(target = "healthProfileName", source = "healthProfileName")
    @Mapping(target = "onlineConsultationId", source = "row.onlineConsultationId")
    @Mapping(target = "prescriptionId", source = "row.prescriptionId")
    @Mapping(target = "doctorName", source = "row.doctorName")
    @Mapping(target = "departmentName", source = "row.departmentName")
    @Mapping(target = "consultationEndsAt", ignore = true)
    @Mapping(target = "updatedAt", source = "row.updatedAt")
    ProgressItem fromTracking(
            OnlineConsultationMapper.PrescriptionTrackingRow row, String statusLabel, String healthProfileName);

    default String timestamp(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
