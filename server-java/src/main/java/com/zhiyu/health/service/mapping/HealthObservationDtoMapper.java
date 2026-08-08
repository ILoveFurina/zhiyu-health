package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.service.HealthObservationService;
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
