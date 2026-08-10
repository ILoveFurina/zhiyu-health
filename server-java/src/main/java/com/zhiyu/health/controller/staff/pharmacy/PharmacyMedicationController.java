package com.zhiyu.health.controller.staff.pharmacy;

import com.zhiyu.health.service.pharmacy.PharmacyMedicationService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 药房药品在售关系维护（票 88，ADR-0035）：admin/pharmacist 可操作（路由级角色授权）。
 * 按 id 定位（路由不再携带 pharmacyId）；删除遵循历史引用规则——被订单/处方引用仅可下架。
 */
@RestController
@RequestMapping("/api/b/pharmacy-medications")
@RequiredArgsConstructor
public class PharmacyMedicationController {

    private final PharmacyMedicationService pharmacyMedicationService;

    public record UpdateMedicationInput(
            @NotNull @DecimalMin("0.00") BigDecimal price, @NotNull @Min(0) Integer stock, @NotNull Boolean isOnSale) {}

    @PutMapping("/{id}")
    public PharmacyMedicationService.PharmacyMedicationView update(
            @PathVariable long id, @Validated @RequestBody UpdateMedicationInput input) {
        return pharmacyMedicationService.update(
                id, new PharmacyMedicationService.UpdateCommand(input.price(), input.stock(), input.isOnSale()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable long id) {
        pharmacyMedicationService.remove(id);
    }
}
