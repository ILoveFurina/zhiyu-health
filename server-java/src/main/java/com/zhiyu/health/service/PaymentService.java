package com.zhiyu.health.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Payment;
import com.zhiyu.health.mapper.PaymentMapper;
import com.zhiyu.health.service.mapping.PaymentDtoMapper;
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
    private final TransactionTemplate transactionTemplate;
    private final Contracts contracts;
    private final PaymentDtoMapper paymentDtos;

    public void createUnpaid(long appointmentId, BigDecimal amount) {
        paymentMapper.insertUnpaid(paymentDtos.toUnpaid(
                appointmentId, amount, contracts.paymentFlow().statuses().get("unpaid")));
    }

    public PaymentView payForPatient(long patientId, long appointmentId) {
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
        if (paymentMapper.markPaid(appointmentId, paid, unpaid) == 0) {
            throw new ApiException(409, "挂号收费状态已变化，请刷新后重试");
        }
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
