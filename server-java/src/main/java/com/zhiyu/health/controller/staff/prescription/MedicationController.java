package com.zhiyu.health.controller.staff.prescription;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.controller.staff.prescription.mapping.MedicationInputMapper;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.service.prescription.MedicationAdminService;
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
 * 标准药品目录管理（票 88，ADR-0035）：仅 admin 角色可操作（/api/b/** 路由级角色授权）。
 * medications 收敛为全局标准目录（无价格/库存/上下架语义——那些在各院区药房），
 * 仅列表与编辑处方属性，不暴露新增/删除；药品身份字段不在编辑范围（保禁忌子图对齐）。
 */
@RestController
@RequestMapping("/api/b/medications")
@RequiredArgsConstructor
public class MedicationController {

    private final MedicationAdminService medicationAdminService;
    private final MedicationInputMapper medicationInputMapper;

    public record MedicationInput(@NotNull @JsonProperty("is_prescription") Boolean isPrescription) {}

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
