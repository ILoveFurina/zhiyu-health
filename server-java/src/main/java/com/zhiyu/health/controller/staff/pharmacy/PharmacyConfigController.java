package com.zhiyu.health.controller.staff.pharmacy;

import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 按院区读药房（票 88，ADR-0035）：admin/pharmacist 可读（路由级角色授权）。
 * 供「医院→院区级联选择」后定位该院区药房；配置写操作收口在 PUT /api/b/campus-pharmacies/{id}。
 */
@RestController
@RequestMapping("/api/b/campuses/{campusId}/pharmacy")
@RequiredArgsConstructor
public class PharmacyConfigController {

    private final CampusPharmacyService campusPharmacyService;

    @GetMapping
    public CampusPharmacy get(@PathVariable long campusId) {
        return campusPharmacyService.requireByCampusId(campusId);
    }
}
