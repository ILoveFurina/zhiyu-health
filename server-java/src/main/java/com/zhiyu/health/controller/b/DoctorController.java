package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Doctor;
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

/** 医生管理：仅 admin 角色可操作，业务在 OrganizationService */
@RestController
@RequestMapping("/api/b/doctors")
public class DoctorController {

    private final OrganizationService organizationService;

    public DoctorController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    public record DoctorInput(
            @NotNull Long departmentId,
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 50) String title,
            @NotBlank String specialty,
            @NotBlank @Size(max = 500) String photoUrl) {
    }

    @GetMapping
    public List<Doctor> list(HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        return organizationService.listDoctors();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Doctor create(@Validated @RequestBody DoctorInput input, HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        Doctor created = organizationService.createDoctor(toEntity(new Doctor(), input));
        if (created == null) {
            throw new ApiException(404, "科室不存在");
        }
        return created;
    }

    @PutMapping("/{id}")
    public Doctor update(@PathVariable long id,
                         @Validated @RequestBody DoctorInput input,
                         HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        Doctor doctor = toEntity(new Doctor(), input);
        doctor.setId(id);
        Doctor updated = organizationService.updateDoctor(doctor);
        if (updated == null) {
            throw new ApiException(404, "医生或科室不存在");
        }
        return updated;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, HttpServletRequest request) {
        AdminGuard.requireAdmin(request);
        if (!organizationService.deleteDoctor(id)) {
            throw new ApiException(404, "医生不存在");
        }
    }

    private Doctor toEntity(Doctor doctor, DoctorInput input) {
        doctor.setDepartmentId(input.departmentId());
        doctor.setName(input.name());
        doctor.setTitle(input.title());
        doctor.setSpecialty(input.specialty());
        doctor.setPhotoUrl(input.photoUrl());
        return doctor;
    }
}
