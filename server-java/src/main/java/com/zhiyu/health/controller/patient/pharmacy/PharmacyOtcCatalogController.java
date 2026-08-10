package com.zhiyu.health.controller.patient.pharmacy;

import com.zhiyu.health.service.pharmacy.PharmacyOtcCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端药房 OTC 目录（票 95）：只读浏览各院区药房在售 OTC 的价格/库存，不下单。
 * 已授权定位带 lng/lat：按真实坐标距离升序并下发 distance_meters；未授权不传，
 * 按医院/院区稳定序返回且不出距离（与 otc-candidates 同口径）。
 */
@Validated
@RestController
@RequestMapping("/api/c")
@RequiredArgsConstructor
public class PharmacyOtcCatalogController {

    private final PharmacyOtcCatalogService service;

    @GetMapping("/pharmacy-otc-catalog")
    public PharmacyOtcCatalogService.OtcCatalogView catalog(
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "lat", required = false) Double lat) {
        return service.catalog(lng, lat);
    }
}
