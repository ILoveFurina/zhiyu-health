package com.zhiyu.health.controller.b;

import com.zhiyu.health.entity.Department;
import com.zhiyu.health.service.OrganizationService;
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

/** 科室管理：仅 admin 角色可操作（AdminInterceptor），业务在 OrganizationService */
@RestController
@RequestMapping("/api/b/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final OrganizationService organizationService;

    public record DepartmentInput(
            @NotNull Long hospitalId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 30) String floor,
            @NotBlank @Size(max = 255) String location) {}

    @GetMapping
    public List<Department> list() {
        return organizationService.listDepartments();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Department create(@Validated @RequestBody DepartmentInput input) {
        return organizationService.createDepartment(toEntity(new Department(), input));
    }

    @PutMapping("/{id}")
    public Department update(@PathVariable long id, @Validated @RequestBody DepartmentInput input) {
        Department department = toEntity(new Department(), input);
        department.setId(id);
        return organizationService.updateDepartment(department);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        organizationService.deleteDepartment(id);
    }

    private Department toEntity(Department department, DepartmentInput input) {
        department.setHospitalId(input.hospitalId());
        department.setName(input.name());
        department.setFloor(input.floor());
        department.setLocation(input.location());
        return department;
    }
}
