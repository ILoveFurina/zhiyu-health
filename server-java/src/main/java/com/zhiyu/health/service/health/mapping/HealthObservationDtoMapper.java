package com.zhiyu.health.service.health.mapping;

import com.zhiyu.health.entity.health.HealthObservation;
import com.zhiyu.health.service.health.HealthObservationService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface HealthObservationDtoMapper {

    @Mapping(target = "nameZh", source = "nameZh")
    @Mapping(target = "valueType", source = "valueType")
    @Mapping(target = "displayValue", source = "displayValue")
    @Mapping(target = "sourceLabel", source = "sourceLabel")
    @Mapping(target = "verificationLabel", source = "verificationLabel")
    HealthObservationService.ObservationView toView(
            HealthObservation observation,
            String nameZh,
            String valueType,
            String displayValue,
            String sourceLabel,
            String verificationLabel);
}
