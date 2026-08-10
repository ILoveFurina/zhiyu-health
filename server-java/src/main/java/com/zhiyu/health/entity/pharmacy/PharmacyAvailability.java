package com.zhiyu.health.entity.pharmacy;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * OTC 候选查询投影（票 88）：跨 pharmacy_medications/campus_pharmacies/hospital_campuses/hospitals
 * 四表 JOIN 的只读行，非表实体；按药房分组后由 service 过滤出能整单满足的院区药房候选。
 * 携带院区坐标供 C 端有定位时真实距离排序（服务端不伪造距离）。
 */
@Getter
@Setter
public class PharmacyAvailability {
    private Long pharmacyMedicationId;
    private Long medicationId;
    private BigDecimal price;
    private Integer stock;

    private Long pharmacyId;
    private String pharmacyDisplayName;
    private BigDecimal deliveryFee;
    private Integer estimatedDeliveryMinutes;

    private String hospitalName;
    private String campusName;
    private String campusAddress;
    private String cityName;
    private Double campusLongitude;
    private Double campusLatitude;
}
