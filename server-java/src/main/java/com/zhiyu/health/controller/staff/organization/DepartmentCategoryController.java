package com.zhiyu.health.controller.staff.organization;

import com.zhiyu.health.controller.staff.organization.mapping.DepartmentCategoryInputMapper;
import com.zhiyu.health.entity.organization.DepartmentCategory;
import com.zhiyu.health.service.organization.DepartmentCategoryAdminService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 院区科室分类管理：仅 admin 角色可操作（/api/b/** 路由级角色授权，admin-only），业务在 DepartmentCategoryAdminService */
@RestController
@RequestMapping("/api/b/department-categories")
@RequiredArgsConstructor
public class DepartmentCategoryController {

    private final DepartmentCategoryAdminService departmentCategoryAdminService;
    private final DepartmentCategoryInputMapper departmentCategoryInputMapper;

    public record DepartmentCategoryInput(
            @NotNull Long campusId, @NotBlank @Size(max = 50) String name, @NotNull Integer sortOrder) {}

    @GetMapping
    public List<DepartmentCategory> list() {
        return departmentCategoryAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentCategory create(@Validated @RequestBody DepartmentCategoryInput input) {
        return departmentCategoryAdminService.create(departmentCategoryInputMapper.toEntity(input));
    }

    @PutMapping("/{id}")
    public DepartmentCategory update(@PathVariable long id, @Validated @RequestBody DepartmentCategoryInput input) {
        DepartmentCategory category = departmentCategoryInputMapper.toEntity(input);
        category.setId(id);
        return departmentCategoryAdminService.update(category);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        departmentCategoryAdminService.delete(id);
    }
}
