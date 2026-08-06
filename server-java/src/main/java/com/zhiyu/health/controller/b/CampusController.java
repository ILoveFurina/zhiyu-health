package com.zhiyu.health.controller.b;

import com.zhiyu.health.controller.b.mapping.CampusInputMapper;
import com.zhiyu.health.entity.HospitalCampus;
import com.zhiyu.health.service.CampusAdminService;
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

/** 院区管理（票 49）：仅 admin 角色可操作（AdminInterceptor），业务在 CampusAdminService */
@RestController
@RequestMapping("/api/b/campuses")
@RequiredArgsConstructor
public class CampusController {

    private final CampusAdminService campusAdminService;
    private final CampusInputMapper campusInputMapper;

    public record CampusInput(
            @NotNull Long hospitalId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 20) String cityCode,
            @NotBlank @Size(max = 50) String cityName,
            @NotBlank @Size(max = 255) String address,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @Size(max = 30) String floor,
            String materials,
            String precautions) {}

    @GetMapping
    public List<HospitalCampus> list() {
        return campusAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HospitalCampus create(@Validated @RequestBody CampusInput input) {
        return campusAdminService.create(campusInputMapper.toEntity(input));
    }

    @PutMapping("/{id}")
    public HospitalCampus update(@PathVariable long id, @Validated @RequestBody CampusInput input) {
        HospitalCampus campus = campusInputMapper.toEntity(input);
        campus.setId(id);
        return campusAdminService.update(campus);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        campusAdminService.delete(id);
    }
}
