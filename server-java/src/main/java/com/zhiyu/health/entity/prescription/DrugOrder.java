package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    // B 端列表展示用：JOIN patients 取昵称，非表列（与 Prescription.patientNickname 同构）
    @TableField(exist = false)
    private String patientNickname;
}
