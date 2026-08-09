package com.zhiyu.health.controller.patient.health.mapping;

import com.zhiyu.health.controller.patient.health.HealthProfileController;
import com.zhiyu.health.service.health.HealthProfileService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface HealthProfileInputMapper {

    @Mapping(target = "patientId", source = "patientId")
    HealthProfileService.CreateCommand toCommand(long patientId, HealthProfileController.CreateInput input);
}
