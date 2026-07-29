package com.zhiyu.health.controller.b.mapping;

import com.zhiyu.health.controller.b.DoctorPrescriptionController;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.PrescriptionService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PrescriptionInputMapper {
    PrescriptionService.CreateItem toCreateItem(DoctorPrescriptionController.ItemInput input);

    @Mapping(target = "staffId", source = "staffId")
    @Mapping(target = "appointmentId", source = "appointmentId")
    @Mapping(target = "notes", source = "input.notes")
    @Mapping(target = "items", source = "input.items")
    PrescriptionService.CreateCommand toCommand(
            long staffId, long appointmentId, DoctorPrescriptionController.CreateInput input);

    @Mapping(target = "staffId", source = "staffId")
    @Mapping(target = "appointmentId", source = "appointmentId")
    @Mapping(target = "medicationIds", source = "input.medicationIds")
    PrescriptionService.CheckSafetyCommand toSafetyCommand(
            long staffId, long appointmentId, DoctorPrescriptionController.SafetyCheckInput input);

    DoctorPrescriptionController.SafetyCheckResponse toSafetyResponse(ContraindicationResult result);
}
