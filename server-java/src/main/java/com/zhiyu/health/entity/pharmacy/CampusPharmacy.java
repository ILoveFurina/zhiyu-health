package com.zhiyu.health.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 院区药房（票 88，ADR-0035）：与院区强一对一（campus_id 唯一），随院区创建事务自动创建。 */
@Getter
@Setter
@TableName("campus_pharmacies")
public class CampusPharmacy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campusId;
    private String displayName;
    private BigDecimal deliveryFee;
    private Integer estimatedDeliveryMinutes;
    private OffsetDateTime createdAt;
}
