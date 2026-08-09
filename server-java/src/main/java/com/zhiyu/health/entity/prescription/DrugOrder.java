package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("drug_orders")
public class DrugOrder {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    // 票 76（ADR-0032）：可空。处方药订单非空（须属该处方明细），OTC 订单为空，由 service 层强校验。
    private Long prescriptionId;
    private String status;
    private BigDecimal totalAmount;
    private OffsetDateTime createdAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime cancelledAt;
}
