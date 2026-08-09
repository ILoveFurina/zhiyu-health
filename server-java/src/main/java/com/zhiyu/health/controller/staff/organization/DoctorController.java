package com.zhiyu.health.controller.staff.organization;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.staff.organization.mapping.DoctorInputMapper;
import com.zhiyu.health.entity.organization.Doctor;
import com.zhiyu.health.service.organization.DoctorAdminService;
import com.zhiyu.health.service.vision.PhotoObjectKeys;
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
@Validated
public class DoctorController {

    private final DoctorAdminService doctorAdminService;
    private final DoctorInputMapper doctorInputMapper;

    public record DoctorInput(
            @NotNull Long departmentId,
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 10) String gender,
            @NotNull java.time.LocalDate birthDate,
            @NotBlank @Size(max = 50) String title,
            @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") BigDecimal registrationFee,
            @NotBlank String specialty,
            // 照片可选（无图留空）；非空时必须是 MinIO object key（票 54：禁止任意 URL 入库）
            @Size(max = 500) String photoUrl) {}

    @GetMapping
    public List<Doctor> list() {
        return doctorAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Doctor create(@Validated @RequestBody DoctorInput input) {
        validatePhotoUrl(input.photoUrl());
        return doctorAdminService.create(doctorInputMapper.toEntity(input));
    }

    @PutMapping("/{id}")
    public Doctor update(@PathVariable long id, @Validated @RequestBody DoctorInput input) {
        validatePhotoUrl(input.photoUrl());
        Doctor doctor = doctorInputMapper.toEntity(input);
        doctor.setId(id);
        return doctorAdminService.update(doctor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        doctorAdminService.delete(id);
    }

    // controller 只做校验与装配：photo_url 为空表示无照片，非空必须形如 MinIO object key
    private void validatePhotoUrl(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return;
        }
        if (!PhotoObjectKeys.isValid(photoUrl)) {
            throw new ApiException(400, "照片必须为有效图片，请重新上传");
        }
    }
}
