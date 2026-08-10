package com.zhiyu.health.controller.staff.pharmacy;

import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import com.zhiyu.health.service.pharmacy.PharmacyMedicationService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 院区药房（票 88，ADR-0035）：admin/pharmacist 可操作（路由级角色授权）。
 * 药房随院区创建自动产生，无独立新增/删除端点：PUT /{id} 仅改展示名/配送费/预计时效；
 * 药品子资源承载在售关系的加入与查询（维护/删除按 id 收口在 /api/b/pharmacy-medications）。
 */
@RestController
@RequestMapping("/api/b/campus-pharmacies")
@RequiredArgsConstructor
public class CampusPharmacyController {

    private final CampusPharmacyService campusPharmacyService;
    private final PharmacyMedicationService pharmacyMedicationService;

    public record ConfigInput(
            @NotBlank @Size(max = 100) String displayName,
            @NotNull @DecimalMin("0.00") BigDecimal deliveryFee,
            @NotNull @Positive Integer estimatedDeliveryMinutes) {}

    public record AddMedicationInput(
            @NotNull Long medicationId,
            @NotNull @DecimalMin("0.00") BigDecimal price,
            @NotNull @Min(0) Integer stock,
            Boolean isOnSale) {}

    @GetMapping
    public List<CampusPharmacy> list() {
        return campusPharmacyService.listAll();
    }

    /** 药房基础配置：只改展示名/配送费/预计时效，campus 一对一关系不可改。 */
    @PutMapping("/{id}")
    public CampusPharmacy updateConfig(@PathVariable long id, @Validated @RequestBody ConfigInput input) {
        return campusPharmacyService.updateConfig(
                id, input.displayName(), input.deliveryFee(), input.estimatedDeliveryMinutes());
    }

    @GetMapping("/{id}/medications")
    public List<PharmacyMedicationService.PharmacyMedicationView> listMedications(
            @PathVariable long id, @RequestParam(required = false) String keyword) {
        return pharmacyMedicationService.listMedications(id, keyword);
    }

    @PostMapping("/{id}/medications")
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyMedicationService.PharmacyMedicationView addMedication(
            @PathVariable long id, @Validated @RequestBody AddMedicationInput input) {
        // 缺省在售：加入目录即可供医生开方/患者查询
        Boolean isOnSale = input.isOnSale() == null ? Boolean.TRUE : input.isOnSale();
        return pharmacyMedicationService.add(
                id,
                new PharmacyMedicationService.AddCommand(input.medicationId(), input.price(), input.stock(), isOnSale));
    }
}
