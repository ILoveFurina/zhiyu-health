package com.zhiyu.health.controller.staff.organization.mapping;

import com.zhiyu.health.controller.staff.organization.CampusController;
import com.zhiyu.health.entity.organization.HospitalCampus;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** CampusInput → HospitalCampus：id 由 controller 按 create/update 语义自行设置。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CampusInputMapper {

    HospitalCampus toEntity(CampusController.CampusInput input);
}
