package com.zhiyu.health.controller.b;

import com.zhiyu.health.entity.Doctor;
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

/** 医生管理：仅 admin 角色可操作（AdminInterceptor），业务在 OrganizationService */
@RestController
@RequestMapping("/api/b/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final OrganizationService organizationService;

    public record DoctorInput(
            @NotNull Long departmentId,
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 50) String title,
            @NotBlank String specialty,
            @NotBlank @Size(max = 500) String photoUrl) {}

    @GetMapping
    public List<Doctor> list() {
        return organizationService.listDoctors();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Doctor create(@Validated @RequestBody DoctorInput input) {
        return organizationService.createDoctor(toEntity(new Doctor(), input));
    }

    @PutMapping("/{id}")
    public Doctor update(@PathVariable long id, @Validated @RequestBody DoctorInput input) {
        Doctor doctor = toEntity(new Doctor(), input);
        doctor.setId(id);
        return organizationService.updateDoctor(doctor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        organizationService.deleteDoctor(id);
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
