package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.service.HealthProfileService;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface HealthProfileDtoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", constant = "true")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    HealthProfile toEntity(HealthProfileService.CreateCommand command);

    HealthProfileService.ProfileView toView(HealthProfile profile, List<String> allergies);
}
