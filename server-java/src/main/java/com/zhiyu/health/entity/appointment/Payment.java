package com.zhiyu.health.entity.appointment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 挂号收费单实体：映射 payments 表，一挂号单对应一支付单。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>{@code appointment_id} 唯一约束 + {@code insertUnpaid} 的 ON CONFLICT 守卫保证幂等建单</li>
 *   <li>状态机取值由契约 {@code payment-flow.json} 定义（unpaid / paid / refunded，票 90 新增 refunded）</li>
 *   <li>本类只管收费行与挂号单状态推进，不动号源（号源由 {@link com.zhiyu.health.service.scheduling.SlotAccounting} 管理）</li>
 * </ul>
 * <p>
 * CAS 守卫：
 * <ul>
 *   <li>{@code markPaid} 只接受 UNPAID → PAID</li>
 *   <li>{@code markRefunded} 只接受 PAID → REFUNDED</li>
 * </ul>
 */
@Getter
@Setter
@TableName("payments")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联挂号单 ID，唯一约束保证一对一关系。 */
    private Long appointmentId;

    /** 挂号费金额，与挂号单保持一致。 */
    private BigDecimal amount;

    /**
     * 收费状态编码，取值由契约定义：
     * <ul>
     *   <li>{@code UNPAID} — 待支付（挂号后初始状态）</li>
     *   <li>{@code PAID} — 已支付</li>
     *   <li>{@code REFUNDED} — 已退款（票 90）</li>
     * </ul>
     */
    private String status;

    /** 记录创建时间，由数据库默认 now() 生成。 */
    private OffsetDateTime createdAt;

    /**
     * 支付完成时间。
     * {@code markPaid} 的 CAS 守卫保证只写一次，不会因并发支付被误写。
     */
    private OffsetDateTime paidAt;

    /**
     * 退款完成时间（票 90）。
     * {@code markRefunded} 的 CAS 守卫保证只写一次，不会因并发取消被误写；
     * 仅 {@code status = REFUNDED} 时非空，未退款的收费单此列为 NULL。
     */
    private OffsetDateTime refundedAt;
}
