package com.zhiyu.health.controller.b;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.controller.b.mapping.MedicationInputMapper;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.service.MedicationAdminService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 药品管理：仅 admin 角色可操作（AdminInterceptor 保护 /api/b/**），业务在 MedicationAdminService。
 * 仅列表与编辑（price/stock/is_active），不暴露新增/删除；药品身份字段不在编辑范围（保禁忌子图对齐）。
 */
@RestController
@RequestMapping("/api/b/medications")
@RequiredArgsConstructor
public class MedicationController {

    private final MedicationAdminService medicationAdminService;
    private final MedicationInputMapper medicationInputMapper;

    public record MedicationInput(
            @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") java.math.BigDecimal price,
            @NotNull @Min(0) Integer stock,
            @NotNull @JsonProperty("is_active") Boolean isActive) {}

    @GetMapping
    public List<Medication> list() {
        return medicationAdminService.listAll();
    }

    @PutMapping("/{id}")
    public Medication update(@PathVariable long id, @Validated @RequestBody MedicationInput input) {
        Medication medication = medicationInputMapper.toEntity(input);
        medication.setId(id);
        return medicationAdminService.update(medication);
    }
}
