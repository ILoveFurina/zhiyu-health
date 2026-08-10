package com.zhiyu.health.service.appointment;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.appointment.Payment;
import com.zhiyu.health.mapper.appointment.AppointmentMapper;
import com.zhiyu.health.mapper.appointment.PaymentMapper;
import com.zhiyu.health.service.appointment.mapping.PaymentDtoMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 挂号收费状态机：管理 Payment 行的 unpaid → paid → refunded 流转，并推进挂号单 pending_payment → booked。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>本类只动 payment 行与挂号单状态，不触碰号源</li>
 *   <li>号源扣减/释放统一由 {@link AppointmentService} 经 {@link com.zhiyu.health.service.scheduling.SlotAccounting} 完成</li>
 *   <li>C 端（{@link #payForPatient}）与 B 端（{@link #payForAdmin}）复用同一锁后状态机</li>
 *   <li>退款（{@link #refundIfPaid}）由取消挂号流程在事务内调用，模拟退款即 PAID → REFUNDED，不接真实支付渠道</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PaymentService extends ServiceImpl<PaymentMapper, Payment> {

    private final PaymentMapper paymentMapper;
    private final AppointmentMapper appointmentMapper;
    private final TransactionTemplate transactionTemplate;
    private final Contracts contracts;
    private final PaymentDtoMapper paymentDtos;

    /**
     * 创建待支付收费单。
     * <p>
     * 在挂号事务提交后异步补建，失败不撤销挂号结果。
     * 幂等：{@code insertUnpaid} 使用 ON CONFLICT 守卫，重复调用不报错。
     * </p>
     *
     * @param appointmentId 挂号单 ID
     * @param amount        挂号费金额
     */
    public void createUnpaid(long appointmentId, BigDecimal amount) {
        paymentMapper.insertUnpaid(paymentDtos.toUnpaid(
                appointmentId, amount, contracts.paymentFlow().statuses().get("unpaid")));
    }

    /**
     * C 端患者支付挂号费。
     * <p>
     * 患者归属校验与收费行锁在同一事务内完成，避免校验后记录被另一入口并发支付。
     * 支付成功后推进挂号单状态 PENDING_PAYMENT → BOOKED。
     * </p>
     *
     * @param patientId     患者 ID
     * @param appointmentId 挂号单 ID
     * @return 支付结果视图
     */
    public PaymentView payForPatient(long patientId, long appointmentId) {
        // 患者归属校验与收费行锁在同一事务内完成，避免校验后记录被另一入口并发支付。
        return transactionTemplate.execute(
                status -> payLocked(paymentMapper.selectForPatientForUpdate(appointmentId, patientId), appointmentId));
    }

    /**
     * B 端管理员查询收费列表。
     *
     * @param status 收费状态筛选（unpaid/paid/refunded），null 表示全部
     * @return 收费视图列表
     */
    public List<PaymentView> listForAdmin(String status) {
        String normalizedStatus = status == null || status.isBlank() ? null : status;
        if (normalizedStatus != null && !contracts.paymentFlow().statusLabels().containsKey(normalizedStatus)) {
            throw new ApiException(400, "挂号收费状态无效");
        }
        return paymentMapper.selectForAdmin(normalizedStatus).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * B 端管理员查询单条收费记录。
     *
     * @param paymentId 收费单 ID
     * @return 收费视图
     */
    public PaymentView getForAdmin(long paymentId) {
        Payment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new ApiException(404, "挂号收费不存在");
        }
        return toView(payment);
    }

    /**
     * B 端管理员模拟支付（演示/对账场景）。
     * <p>
     * 与 C 端复用同一锁后状态机，任一入口先提交后，另一入口只会得到已支付冲突。
     * </p>
     *
     * @param paymentId 收费单 ID
     * @return 支付结果视图
     */
    public PaymentView payForAdmin(long paymentId) {
        // B 端与 C 端复用同一锁后状态机，任一入口先提交后，另一入口只会得到已支付冲突。
        return transactionTemplate.execute(status -> {
            Payment payment = paymentMapper.selectForUpdate(paymentId);
            return payLocked(payment, payment == null ? 0L : payment.getAppointmentId());
        });
    }

    /**
     * 模拟退款（票 90）：把已支付收费单 PAID → REFUNDED。
     * <p>
     * 必须在取消挂号的事务内调用，与 {@code markCancelled} + 号源回补同提交同回滚。
     * CAS 守卫幂等：{@code markRefunded} 返回 1 表示首次退款成功，返回 0 表示已退款（并发重复取消）
     * 或非 PAID（未支付取消），均安全跳过不抛异常。不接真实支付渠道，退款即状态推进 + 写 refunded_at。
     * </p>
     *
     * @param appointmentId 挂号单 ID
     * @return true 表示实际执行了退款（PAID → REFUNDED）；false 表示无需退款或已退过
     */
    public boolean refundIfPaid(long appointmentId) {
        String paid = contracts.paymentFlow().statuses().get("paid");
        String refunded = contracts.paymentFlow().statuses().get("refunded");
        return paymentMapper.markRefunded(appointmentId, refunded, paid) == 1;
    }

    /**
     * 支付状态机核心：在已加锁的收费记录上执行 UNPAID → PAID 推进。
     * <p>
     * 执行步骤：
     * <ol>
     *   <li>校验收费单存在且状态为 UNPAID</li>
     *   <li>CAS 更新 payment 为 PAID（条件：原状态 UNPAID + 挂号单状态 PENDING_PAYMENT）</li>
     *   <li>推进挂号单状态 PENDING_PAYMENT → BOOKED</li>
     * </ol>
     * <p>
     * 极端竞态：若挂号单已被超时收敛为 CANCELLED，{@code markBooked} 返回 0，但不回滚已 markPaid 的
     * payment 行——收费已发生属事实，留作审计痕迹，后续由退款/对账流程处理。
     *
     * @param payment       已加锁的收费记录
     * @param appointmentId 关联挂号单 ID
     * @return 支付结果视图
     */
    private PaymentView payLocked(Payment payment, long appointmentId) {
        if (payment == null) {
            throw new ApiException(404, "挂号收费不存在");
        }
        String unpaid = contracts.paymentFlow().statuses().get("unpaid");
        if (!unpaid.equals(payment.getStatus())) {
            throw new ApiException(409, contracts.paymentFlow().messages().get("already_paid"));
        }
        String paid = contracts.paymentFlow().statuses().get("paid");
        String pendingPayment = contracts.appointmentFlow().status("pending_payment");
        if (paymentMapper.markPaid(appointmentId, paid, unpaid, pendingPayment) == 0) {
            throw new ApiException(409, "挂号已取消或状态已变化，无法支付");
        }
        // 支付完成推进挂号单 PENDING_PAYMENT -> BOOKED（票 81）：CAS 只接受待支付。
        // 模拟支付下不堆并发防御：返回 0 仅在支付与超时收敛极端竞态时出现，demo 不会触发。
        // 即使 markBooked 返回 0（挂号单已被超时收敛为 cancelled），也不回滚已 markPaid 的 payment 行--
        // 收费已发生属事实，留作审计痕迹，后续由退款/对账流程处理，不在支付事务内补偿。
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        appointmentMapper.markBooked(appointmentId, flow.status("pending_payment"), flow.status("booked"));
        payment.setStatus(paid);
        payment.setPaidAt(OffsetDateTime.now());
        return toView(payment);
    }

    /**
     * 将 Payment 实体转换为视图 DTO。
     *
     * @param payment 收费实体
     * @return 收费视图
     */
    private PaymentView toView(Payment payment) {
        String unpaid = contracts.paymentFlow().statuses().get("unpaid");
        String statusLabel = contracts.paymentFlow().statusLabels().get(payment.getStatus());
        return paymentDtos.toView(payment, statusLabel, unpaid.equals(payment.getStatus()));
    }

    /**
     * 收费单视图 DTO。
     *
     * @param id            收费单 ID
     * @param appointmentId 关联挂号单 ID
     * @param amount        金额
     * @param status        状态编码
     * @param statusLabel   状态显示文本
     * @param createdAt     创建时间
     * @param paidAt        支付完成时间
     * @param refundedAt    退款完成时间
     * @param payable       是否允许支付（UNPAID 状态为 true）
     */
    public record PaymentView(
            Long id,
            Long appointmentId,
            BigDecimal amount,
            String status,
            String statusLabel,
            String createdAt,
            String paidAt,
            String refundedAt,
            boolean payable) {}
}
