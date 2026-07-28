package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.service.OrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

import java.util.List;

/** 科室管理：仅 admin 角色可操作，业务在 OrganizationService */
@RestController
@RequestMapping("/api/b/departments")
public class DepartmentController {

    private final OrganizationService organizationService;

    public DepartmentController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    public record DepartmentInput(
            @NotNull Long hospitalId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 30) String floor,
            @NotBlank @Size(max = 255) String location) {
    }

    @GetMapping
    public List<Department> list(HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        return organizationService.listDepartments();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Department create(@Validated @RequestBody DepartmentInput input, HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        Department created = organizationService.createDepartment(toEntity(new Department(), input));
        if (created == null) {
            throw new ApiException(404, "医院不存在");
        }
        return created;
    }

    @PutMapping("/{id}")
    public Department update(@PathVariable long id,
                             @Validated @RequestBody DepartmentInput input,
                             HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        Department department = toEntity(new Department(), input);
        department.setId(id);
        Department updated = organizationService.updateDepartment(department);
        if (updated == null) {
            throw new ApiException(404, "科室或医院不存在");
        }
        return updated;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        if (!organizationService.deleteDepartment(id)) {
            throw new ApiException(404, "科室不存在");
        }
    }

    private Department toEntity(Department department, DepartmentInput input) {
        department.setHospitalId(input.hospitalId());
        department.setName(input.name());
        department.setFloor(input.floor());
        department.setLocation(input.location());
        return department;
    }
}
