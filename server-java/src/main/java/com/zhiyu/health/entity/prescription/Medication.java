package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 标准药品目录（票 88，ADR-0035）：全局目录只承载药品身份与知识字段，
 * 价格/库存/在售语义已收敛到各院区药房的 pharmacy_medications。
 */
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
    // 票 76（ADR-0032）：处方药 TRUE 须凭已审核处方，OTC FALSE 可直接下单；DEFAULT TRUE 偏安全。
    private Boolean isPrescription;
}
