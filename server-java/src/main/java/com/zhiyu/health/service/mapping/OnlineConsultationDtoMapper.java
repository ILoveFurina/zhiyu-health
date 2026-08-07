package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.OnlineConsultation;
import com.zhiyu.health.entity.OnlineConsultationMessage;
import com.zhiyu.health.mapper.OnlineConsultationMapper;
import com.zhiyu.health.service.OnlineConsultationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OnlineConsultationDtoMapper {

    OnlineConsultationService.ConsultationSummaryView toSummaryView(OnlineConsultation consultation);

    @Mapping(target = "statusLabel", source = "statusLabel")
    OnlineConsultationService.ConsultationListItem toListItem(OnlineConsultation consultation, String statusLabel);

    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "progressStep", source = "progressStep")
    @Mapping(target = "consultMethodLabel", source = "consultMethodLabel")
    @Mapping(target = "doctor", source = "doctor")
    @Mapping(target = "terminalHint", source = "terminalHint")
    OnlineConsultationService.ConsultationDetail toDetail(
            OnlineConsultation consultation,
            OnlineConsultationService.ConsultationSummaryView summary,
            String statusLabel,
            String progressStep,
            String consultMethodLabel,
            OnlineConsultationService.DoctorView doctor,
            String terminalHint);

    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "consultMethodLabel", source = "consultMethodLabel")
    @Mapping(target = "patient", source = "patient")
    @Mapping(target = "healthProfile", source = "healthProfile")
    OnlineConsultationService.DoctorListItem toDoctorListItem(
            OnlineConsultation consultation,
            OnlineConsultationService.ConsultationSummaryView summary,
            String statusLabel,
            String consultMethodLabel,
            OnlineConsultationService.PatientRef patient,
            OnlineConsultationService.ProfileRef healthProfile);

    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "consultMethodLabel", source = "consultMethodLabel")
    @Mapping(target = "patient", source = "patient")
    @Mapping(target = "healthProfile", source = "healthProfile")
    OnlineConsultationService.DoctorConsultationDetail toDoctorDetail(
            OnlineConsultation consultation,
            OnlineConsultationService.ConsultationSummaryView summary,
            String statusLabel,
            String consultMethodLabel,
            OnlineConsultationService.PatientRef patient,
            OnlineConsultationService.ProfileRef healthProfile);

    @Mapping(target = "nickname", source = "patientNickname")
    OnlineConsultationService.PatientRef toPatientRef(OnlineConsultation consultation);

    @Mapping(target = "displayName", source = "consultation.profileDisplayName")
    @Mapping(target = "gender", source = "consultation.profileGender")
    @Mapping(target = "birthDate", source = "consultation.profileBirthDate")
    @Mapping(target = "relationship", source = "consultation.profileRelationship")
    @Mapping(target = "allergies", source = "allergies")
    OnlineConsultationService.ProfileRef toProfileRef(OnlineConsultation consultation, List<String> allergies);

    // 表达式须全限定类名：生成的 Impl 在 service.mapping 包，无 PhotoUrls import
    @Mapping(target = "photoUrl", expression = "java(com.zhiyu.health.service.PhotoUrls.cUrl(row.photoUrl()))")
    OnlineConsultationService.DoctorView toDoctorView(OnlineConsultationMapper.DoctorIdentityRow row);

    OnlineConsultationService.MessageView toMessageView(OnlineConsultationMessage message);

    default String map(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    default String map(LocalDate value) {
        return value == null ? null : value.toString();
    }
}
