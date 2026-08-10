package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 药品订单（票 88，ADR-0035）：院区药房整单履约模型。创建时固化履约药房、取药方式、
 * 支付截止、金额快照（药品金额/配送费/总额）、药房/医院/院区/自取地址快照与配送收货信息
 * 一次性快照（PICKUP 必全 NULL，CHECK 兜底）；后续药房配置变化不影响历史订单。
 */
@Getter
@Setter
@TableName("drug_orders")
public class DrugOrder {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    // 可空（票 76，ADR-0032）：处方药订单非空，OTC 订单为空，由 service 层强校验。
    private Long prescriptionId;
    private Long pharmacyId;
    private String pickupMethod;
    private String status;
    private BigDecimal medicationAmount;
    private BigDecimal deliveryFee;
    private BigDecimal totalAmount;

    // 履约快照：药房/医院/院区名与自取地址（即院区地址）
    private String pharmacyName;
    private String hospitalName;
    private String campusName;
    private String campusAddress;

    // 配送收货信息一次性快照（不建地址簿）；自取订单不得落收货信息
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;

    // 模拟配送：进入 SHIPPED 时生成虚构承运方与唯一虚构物流单号
    private String carrierName;
    private String trackingNo;

    private OffsetDateTime paymentDeadline;
    private OffsetDateTime createdAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime dispensingAt;
    private OffsetDateTime shippedAt;
    private OffsetDateTime deliveredAt;
    private OffsetDateTime readyForPickupAt;
    private OffsetDateTime pickedUpAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime expiredAt;

    // B 端列表展示用：JOIN patients 取昵称，非表列（与 Prescription.patientNickname 同构）
    @TableField(exist = false)
    private String patientNickname;
}
