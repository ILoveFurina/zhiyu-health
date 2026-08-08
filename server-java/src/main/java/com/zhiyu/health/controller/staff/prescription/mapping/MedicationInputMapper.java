package com.zhiyu.health.controller.staff.prescription.mapping;

import com.zhiyu.health.controller.staff.prescription.MedicationController;
import com.zhiyu.health.entity.prescription.Medication;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MedicationInputMapper {
    Medication toEntity(MedicationController.MedicationInput input);
}
