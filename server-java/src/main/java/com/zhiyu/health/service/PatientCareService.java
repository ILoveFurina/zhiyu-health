package com.zhiyu.health.service;

import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.mapper.InAppMessageMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PatientCareService {
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper itemMapper;
    private final InAppMessageMapper messageMapper;

    public List<PatientPrescriptionView> approvedPrescriptions(long patientId) {
        // 查询层显式限定 APPROVED，是患者可见性的最后一道确定性边界。
        return prescriptionMapper.selectApprovedForPatient(patientId).stream()
                .map(this::toPrescriptionView)
                .toList();
    }

    public List<MessageView> messages(long patientId) {
        return messageMapper.selectForPatient(patientId).stream()
                .map(message -> new MessageView(
                        message.getId(),
                        message.getType(),
                        message.getTitle(),
                        message.getContent(),
                        message.getDisclaimer(),
                        message.getCreatedAt() == null
                                ? null
                                : message.getCreatedAt().toString()))
                .toList();
    }

    private PatientPrescriptionView toPrescriptionView(Prescription prescription) {
        List<PatientItemView> items = itemMapper.selectDetailed(prescription.getId()).stream()
                .map(this::toItemView)
                .toList();
        return new PatientPrescriptionView(
                prescription.getId(),
                prescription.getDoctorName(),
                prescription.getDepartmentName(),
                prescription.getScheduleDate() == null
                        ? null
                        : prescription.getScheduleDate().toString(),
                prescription.getInterpretation(),
                prescription.getDisclaimer(),
                items);
    }

    private PatientItemView toItemView(PrescriptionItem item) {
        return new PatientItemView(
                item.getMedicationName(),
                item.getSpecification(),
                item.getDosage(),
                item.getFrequency(),
                item.getDuration(),
                item.getNotes());
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
