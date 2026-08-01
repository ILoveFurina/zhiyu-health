package com.zhiyu.health.controller.b;

import com.zhiyu.health.controller.b.mapping.DoctorInputMapper;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.service.DoctorAdminService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

/** 医生管理：仅 admin 角色可操作（AdminInterceptor），业务在 DoctorAdminService */
@RestController
@RequestMapping("/api/b/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final DoctorAdminService doctorAdminService;
    private final DoctorInputMapper doctorInputMapper;

    public record DoctorInput(
            @NotNull Long departmentId,
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 50) String title,
            @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") BigDecimal registrationFee,
            @NotBlank String specialty,
            @NotBlank @Size(max = 500) String photoUrl) {}

    @GetMapping
    public List<Doctor> list() {
        return doctorAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Doctor create(@Validated @RequestBody DoctorInput input) {
        return doctorAdminService.create(doctorInputMapper.toEntity(input));
    }

    @PutMapping("/{id}")
    public Doctor update(@PathVariable long id, @Validated @RequestBody DoctorInput input) {
        Doctor doctor = doctorInputMapper.toEntity(input);
        doctor.setId(id);
        return doctorAdminService.update(doctor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        doctorAdminService.delete(id);
    }
}
