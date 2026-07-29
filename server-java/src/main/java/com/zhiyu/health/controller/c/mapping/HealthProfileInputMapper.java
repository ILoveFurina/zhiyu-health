package com.zhiyu.health.controller.c.mapping;

import com.zhiyu.health.controller.c.HealthProfileController;
import com.zhiyu.health.service.HealthProfileService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface HealthProfileInputMapper {

    @Mapping(target = "patientId", source = "patientId")
    HealthProfileService.CreateCommand toCommand(long patientId, HealthProfileController.CreateInput input);
}
