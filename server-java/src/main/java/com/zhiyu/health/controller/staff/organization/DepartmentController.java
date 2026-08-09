package com.zhiyu.health.controller.staff.organization;

import com.zhiyu.health.controller.staff.organization.mapping.DepartmentInputMapper;
import com.zhiyu.health.entity.organization.Department;
import com.zhiyu.health.service.organization.DepartmentAdminService;
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

/** 科室管理：仅 admin 角色可操作（AdminInterceptor），业务在 DepartmentAdminService */
@RestController
@RequestMapping("/api/b/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentAdminService departmentAdminService;
    private final DepartmentInputMapper departmentInputMapper;

    public record DepartmentInput(
            @NotNull Long campusId,
            @NotNull Long categoryId,
            @NotNull Long standardDepartmentId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 30) String floor,
            @Size(max = 255) String location) {}

    @GetMapping
    public List<Department> list() {
        return departmentAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Department create(@Validated @RequestBody DepartmentInput input) {
        return departmentAdminService.create(departmentInputMapper.toEntity(input));
    }

    @PutMapping("/{id}")
    public Department update(@PathVariable long id, @Validated @RequestBody DepartmentInput input) {
        Department department = departmentInputMapper.toEntity(input);
        department.setId(id);
        return departmentAdminService.update(department);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        departmentAdminService.delete(id);
    }
}
