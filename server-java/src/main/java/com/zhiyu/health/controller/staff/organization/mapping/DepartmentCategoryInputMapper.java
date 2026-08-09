package com.zhiyu.health.controller.staff.organization.mapping;

import com.zhiyu.health.controller.staff.organization.DepartmentCategoryController;
import com.zhiyu.health.entity.organization.DepartmentCategory;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** DepartmentCategoryInput → DepartmentCategory：id 由 controller 按 create/update 语义自行设置。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DepartmentCategoryInputMapper {

    DepartmentCategory toEntity(DepartmentCategoryController.DepartmentCategoryInput input);
}
