package com.zhiyu.health.service.prescription.mapping;

import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.entity.prescription.PrescriptionItem;
import com.zhiyu.health.service.consultation.PatientCareService;
import com.zhiyu.health.service.prescription.PrescriptionService;
import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface PrescriptionDtoMapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "appointmentId", source = "command.appointmentId")
    @Mapping(target = "doctorId", source = "doctorId")
    @Mapping(target = "sourceCampusId", source = "sourceCampusId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "notes", source = "command.notes", qualifiedByName = "trimToNull")
    Prescription toPrescription(
            PrescriptionService.CreateCommand command, long doctorId, long sourceCampusId, String status);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "onlineConsultationId", source = "command.onlineConsultationId")
    @Mapping(target = "doctorId", source = "doctorId")
    @Mapping(target = "sourceCampusId", source = "sourceCampusId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "notes", source = "command.notes", qualifiedByName = "trimToNull")
    Prescription toOnlinePrescription(
            PrescriptionService.CreateOnlineCommand command, long doctorId, long sourceCampusId, String status);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "prescriptionId", source = "prescriptionId")
    @Mapping(target = "medicationId", source = "input.medicationId")
    @Mapping(target = "dosage", source = "input.dosage", qualifiedByName = "trimRequired")
    @Mapping(target = "frequency", source = "input.frequency", qualifiedByName = "trimRequired")
    @Mapping(target = "duration", source = "input.duration", qualifiedByName = "trimRequired")
    @Mapping(target = "quantity", source = "input.quantity")
    @Mapping(target = "notes", source = "input.notes", qualifiedByName = "trimToNull")
    PrescriptionItem toPrescriptionItem(PrescriptionService.CreateItem input, long prescriptionId);

    PrescriptionService.MedicationView toMedicationView(Medication medication);

    @Mapping(target = "name", source = "medicationName")
    PrescriptionService.ItemView toItemView(PrescriptionItem item);

    List<PrescriptionService.ItemView> toItemViews(List<PrescriptionItem> items);

    @Mapping(target = "medicationId", source = "medication.id")
    @Mapping(target = "name", source = "medication.name")
    @Mapping(target = "specification", source = "medication.specification")
    @Mapping(target = "dosage", source = "input.dosage")
    @Mapping(target = "frequency", source = "input.frequency")
    @Mapping(target = "duration", source = "input.duration")
    @Mapping(target = "quantity", source = "input.quantity")
    @Mapping(target = "notes", source = "input.notes")
    PrescriptionService.ItemView toCreatedItem(PrescriptionService.CreateItem input, Medication medication);

    @Mapping(target = "id", source = "prescription.id")
    @Mapping(target = "appointmentId", source = "prescription.appointmentId")
    @Mapping(target = "status", source = "statusLabel")
    @Mapping(target = "notes", source = "prescription.notes")
    @Mapping(target = "interpretation", source = "prescription.interpretation")
    @Mapping(target = "disclaimer", source = "prescription.disclaimer")
    @Mapping(target = "patientNickname", source = "prescription.patientNickname")
    @Mapping(target = "doctorName", source = "prescription.doctorName")
    PrescriptionService.PrescriptionView toPrescriptionView(
            Prescription prescription,
            String statusLabel,
            String sourceType,
            String sourceTypeLabel,
            String date,
            List<PrescriptionService.ItemView> items);

    @Mapping(target = "name", source = "medicationName")
    PatientCareService.PatientItemView toPatientItemView(PrescriptionItem item);

    List<PatientCareService.PatientItemView> toPatientItemViews(List<PrescriptionItem> items);

    @Mapping(target = "id", source = "prescription.id")
    @Mapping(target = "status", source = "prescription.status")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "reviewReason", source = "prescription.reviewReason")
    @Mapping(target = "sourceId", source = "sourceId")
    @Mapping(target = "doctorName", source = "prescription.doctorName")
    @Mapping(target = "departmentName", source = "prescription.departmentName")
    @Mapping(target = "interpretation", source = "prescription.interpretation")
    @Mapping(target = "disclaimer", source = "prescription.disclaimer")
    PatientCareService.PatientPrescriptionView toPatientPrescriptionView(
            Prescription prescription,
            String sourceType,
            String statusLabel,
            Long sourceId,
            String date,
            List<PatientCareService.PatientItemView> items);

    @Mapping(target = "id", source = "message.id")
    @Mapping(target = "type", source = "message.type")
    @Mapping(target = "title", source = "message.title")
    @Mapping(target = "content", source = "message.content")
    @Mapping(target = "disclaimer", source = "message.disclaimer")
    @Mapping(target = "createdAt", source = "createdAtText")
    PatientCareService.MessageView toMessageView(InAppMessage message, String createdAtText);

    @Named("trimRequired")
    default String trimRequired(String value) {
        return value.trim();
    }

    @Named("trimToNull")
    default String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
