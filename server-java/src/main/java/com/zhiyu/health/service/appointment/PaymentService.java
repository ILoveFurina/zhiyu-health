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

@Service
@RequiredArgsConstructor
public class PaymentService extends ServiceImpl<PaymentMapper, Payment> {

    private final PaymentMapper paymentMapper;
    private final AppointmentMapper appointmentMapper;
    private final TransactionTemplate transactionTemplate;
    private final Contracts contracts;
    private final PaymentDtoMapper paymentDtos;

    public void createUnpaid(long appointmentId, BigDecimal amount) {
        paymentMapper.insertUnpaid(paymentDtos.toUnpaid(
                appointmentId, amount, contracts.paymentFlow().statuses().get("unpaid")));
    }

    public PaymentView payForPatient(long patientId, long appointmentId) {
        // 患者归属校验与收费行锁在同一事务内完成，避免校验后记录被另一入口并发支付。
        return transactionTemplate.execute(
                status -> payLocked(paymentMapper.selectForPatientForUpdate(appointmentId, patientId), appointmentId));
    }

    public List<PaymentView> listForAdmin(String status) {
        String normalizedStatus = status == null || status.isBlank() ? null : status;
        if (normalizedStatus != null && !contracts.paymentFlow().statusLabels().containsKey(normalizedStatus)) {
            throw new ApiException(400, "挂号收费状态无效");
        }
        return paymentMapper.selectForAdmin(normalizedStatus).stream()
                .map(this::toView)
                .toList();
    }

    public PaymentView getForAdmin(long paymentId) {
        Payment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new ApiException(404, "挂号收费不存在");
        }
        return toView(payment);
    }

    public PaymentView payForAdmin(long paymentId) {
        // B 端与 C 端复用同一锁后状态机，任一入口先提交后，另一入口只会得到已支付冲突。
        return transactionTemplate.execute(status -> {
            Payment payment = paymentMapper.selectForUpdate(paymentId);
            return payLocked(payment, payment == null ? 0L : payment.getAppointmentId());
        });
    }

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
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        appointmentMapper.markBooked(appointmentId, flow.status("pending_payment"), flow.status("booked"));
        payment.setStatus(paid);
        payment.setPaidAt(OffsetDateTime.now());
        return toView(payment);
    }

    private PaymentView toView(Payment payment) {
        String unpaid = contracts.paymentFlow().statuses().get("unpaid");
        String statusLabel = contracts.paymentFlow().statusLabels().get(payment.getStatus());
        return paymentDtos.toView(payment, statusLabel, unpaid.equals(payment.getStatus()));
    }

    public record PaymentView(
            Long id,
            Long appointmentId,
            BigDecimal amount,
            String status,
            String statusLabel,
            String createdAt,
            String paidAt,
            boolean payable) {}
}
