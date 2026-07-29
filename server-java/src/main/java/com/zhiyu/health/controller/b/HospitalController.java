package com.zhiyu.health.controller.b;

import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.service.OrganizationService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

/** 医院管理：仅 admin 角色可操作（AdminInterceptor），业务在 OrganizationService */
@RestController
@RequestMapping("/api/b/hospitals")
@RequiredArgsConstructor
public class HospitalController {

    private final OrganizationService organizationService;

    public record HospitalInput(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 30) String level,
            @NotBlank @Size(max = 255) String address,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude) {}

    @GetMapping
    public List<Hospital> list() {
        return organizationService.listHospitals();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Hospital create(@Validated @RequestBody HospitalInput input) {
        return organizationService.createHospital(toEntity(new Hospital(), input));
    }

    @PutMapping("/{id}")
    public Hospital update(@PathVariable long id, @Validated @RequestBody HospitalInput input) {
        Hospital hospital = toEntity(new Hospital(), input);
        hospital.setId(id);
        return organizationService.updateHospital(hospital);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        organizationService.deleteHospital(id);
    }

    private Hospital toEntity(Hospital hospital, HospitalInput input) {
        hospital.setName(input.name());
        hospital.setLevel(input.level());
        hospital.setAddress(input.address());
        hospital.setLongitude(input.longitude());
        hospital.setLatitude(input.latitude());
        return hospital;
    }
}
