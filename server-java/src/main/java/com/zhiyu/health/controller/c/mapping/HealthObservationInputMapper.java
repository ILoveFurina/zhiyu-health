package com.zhiyu.health.controller.c.mapping;

import com.zhiyu.health.controller.c.HealthObservationController;
import com.zhiyu.health.service.HealthObservationService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface HealthObservationInputMapper {

    @Mapping(target = "patientId", source = "patientId")
    @Mapping(target = "observationId", source = "observationId")
    @Mapping(target = "value", source = "input.value")
    HealthObservationService.CorrectCommand toCommand(
            long patientId, long observationId, HealthObservationController.CorrectInput input);
}
