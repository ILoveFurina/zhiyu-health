package com.zhiyu.health.controller.b.mapping;

import com.zhiyu.health.controller.b.MedicationController;
import com.zhiyu.health.entity.Medication;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MedicationInputMapper {
    Medication toEntity(MedicationController.MedicationInput input);
}
