package com.zhiyu.health.controller.staff.organization.mapping;

import com.zhiyu.health.controller.staff.organization.StandardDepartmentController;
import com.zhiyu.health.entity.organization.StandardDepartment;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** StandardDepartmentInput → StandardDepartment：id 由 controller 按 create/update 语义自行设置。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface StandardDepartmentInputMapper {

    StandardDepartment toEntity(StandardDepartmentController.StandardDepartmentInput input);
}
