package com.zhiyu.health.controller.b.mapping;

import com.zhiyu.health.controller.b.DepartmentController;
import com.zhiyu.health.entity.Department;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** DepartmentInput → Department：id 由 controller 按 create/update 语义自行设置。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DepartmentInputMapper {

    Department toEntity(DepartmentController.DepartmentInput input);
}
