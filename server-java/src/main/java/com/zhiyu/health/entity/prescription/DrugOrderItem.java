package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("drug_order_items")
public class DrugOrderItem {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long drugOrderId;
    private Long medicationId;
    // 票 88（ADR-0035）：成交时锁定的药房药品在售关系快照；下架/换价后历史订单仍可追溯。
    private Long pharmacyMedicationId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;

    @TableField(exist = false)
    private String medicationName;

    @TableField(exist = false)
    private String specification;
}
