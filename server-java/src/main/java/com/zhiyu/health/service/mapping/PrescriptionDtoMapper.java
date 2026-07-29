package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.InAppMessage;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.service.PatientCareService;
import com.zhiyu.health.service.PrescriptionService;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PrescriptionDtoMapper {
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
            Prescription prescription, String statusLabel, String date, List<PrescriptionService.ItemView> items);

    @Mapping(target = "name", source = "medicationName")
    PatientCareService.PatientItemView toPatientItemView(PrescriptionItem item);

    List<PatientCareService.PatientItemView> toPatientItemViews(List<PrescriptionItem> items);

    @Mapping(target = "id", source = "prescription.id")
    @Mapping(target = "doctorName", source = "prescription.doctorName")
    @Mapping(target = "departmentName", source = "prescription.departmentName")
    @Mapping(target = "interpretation", source = "prescription.interpretation")
    @Mapping(target = "disclaimer", source = "prescription.disclaimer")
    PatientCareService.PatientPrescriptionView toPatientPrescriptionView(
            Prescription prescription, String date, List<PatientCareService.PatientItemView> items);

    @Mapping(target = "id", source = "message.id")
    @Mapping(target = "type", source = "message.type")
    @Mapping(target = "title", source = "message.title")
    @Mapping(target = "content", source = "message.content")
    @Mapping(target = "disclaimer", source = "message.disclaimer")
    @Mapping(target = "createdAt", source = "createdAtText")
    PatientCareService.MessageView toMessageView(InAppMessage message, String createdAtText);
}
