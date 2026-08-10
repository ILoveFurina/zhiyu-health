package com.zhiyu.health.entity.pharmacy;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 药房 OTC 目录查询投影（票 95）：跨 pharmacy_medications/medications/campus_pharmacies/
 * hospital_campuses/hospitals 五表 JOIN 的只读行，非表实体；按药房分组后由 service 装配
 * C 端目录视图。携带在售/处方标记供 service 兜底守卫，携带院区坐标供有定位时真实距离排序
 * （服务端不伪造距离）。
 */
@Getter
@Setter
public class PharmacyOtcCatalogRow {
    private Long pharmacyMedicationId;
    private Long medicationId;
    private BigDecimal price;
    private Integer stock;
    private Boolean isOnSale;

    private String medicationName;
    private String genericName;
    private String specification;
    private Boolean isPrescription;

    private Long pharmacyId;
    private String pharmacyDisplayName;
    private BigDecimal deliveryFee;
    private Integer estimatedDeliveryMinutes;

    private String hospitalName;
    private String campusName;
    private String campusAddress;
    private Double campusLongitude;
    private Double campusLatitude;
}
