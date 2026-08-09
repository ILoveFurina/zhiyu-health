package com.zhiyu.health.controller.staff.organization.mapping;

import com.zhiyu.health.controller.staff.organization.DoctorController;
import com.zhiyu.health.entity.organization.Doctor;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** DoctorInput → Doctor：id 由 controller 按 create/update 语义自行设置。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DoctorInputMapper {

    Doctor toEntity(DoctorController.DoctorInput input);
}
