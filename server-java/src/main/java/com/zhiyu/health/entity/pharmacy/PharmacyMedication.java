package com.zhiyu.health.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 药房药品（票 88，ADR-0035）：某院区药房对一种标准药品的在售关系，价格/库存/在售各药房独立。 */
@Getter
@Setter
@TableName("pharmacy_medications")
public class PharmacyMedication {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pharmacyId;
    private Long medicationId;
    private BigDecimal price;
    private Integer stock;
    private Boolean isOnSale;

    // 以下为 JOIN medications 的展示投影，非本表列（与 Prescription.patientNickname 同构）
    @TableField(exist = false)
    private String medicationName;

    @TableField(exist = false)
    private String genericName;

    @TableField(exist = false)
    private String specification;

    @TableField(exist = false)
    private Boolean isPrescription;
}
