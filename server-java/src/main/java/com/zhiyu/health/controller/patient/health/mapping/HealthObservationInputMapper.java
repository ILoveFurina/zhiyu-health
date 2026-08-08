package com.zhiyu.health.controller.patient.health.mapping;

import com.zhiyu.health.controller.patient.health.HealthObservationController;
import com.zhiyu.health.service.health.HealthObservationService;
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
