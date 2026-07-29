package com.zhiyu.health.service;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.service.mapping.PrescriptionDtoMapper;
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

    public List<PatientPrescriptionView> approvedPrescriptions(long patientId) {
        // 查询层显式限定 APPROVED，是患者可见性的最后一道确定性边界。
        String approved = contracts.prescriptionFlow().statuses().get("approved");
        long profileId = healthProfiles.requireActive(patientId).getId();
        return prescriptionMapper.selectApprovedForProfile(patientId, profileId, approved).stream()
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
        return dtoMapper.toPatientPrescriptionView(prescription, date, items);
    }

    public record PatientItemView(
            String name, String specification, String dosage, String frequency, String duration, String notes) {}

    public record PatientPrescriptionView(
            Long id,
            String doctorName,
            String departmentName,
            String date,
            String interpretation,
            String disclaimer,
            List<PatientItemView> items) {}

    public record MessageView(
            Long id, String type, String title, String content, String disclaimer, String createdAt) {}
}
