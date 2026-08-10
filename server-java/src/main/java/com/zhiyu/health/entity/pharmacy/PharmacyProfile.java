package com.zhiyu.health.entity.pharmacy;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 药房档案投影（票 88）：campus_pharmacies/hospital_campuses/hospitals 三表 JOIN 的只读行，
 * 非表实体；供下单履约快照（药房/医院/院区名与自取地址）与购药预览的药房卡片取数。
 */
@Getter
@Setter
public class PharmacyProfile {
    private Long pharmacyId;
    private Long campusId;
    private String displayName;
    private BigDecimal deliveryFee;
    private Integer estimatedDeliveryMinutes;
    private String hospitalName;
    private String campusName;
    private String campusAddress;
    private String cityName;
    private Double campusLongitude;
    private Double campusLatitude;
}
