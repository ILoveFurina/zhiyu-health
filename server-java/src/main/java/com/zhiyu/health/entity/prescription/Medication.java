package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("medications")
public class Medication {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String genericName;
    private String specification;
    private String instructions;
    private BigDecimal price;
    private Integer stock;
    private Boolean isActive;
    // 票 76（ADR-0032）：处方药 TRUE 须凭已审核处方，OTC FALSE 可直接下单；DEFAULT TRUE 偏安全。
    private Boolean isPrescription;
}
