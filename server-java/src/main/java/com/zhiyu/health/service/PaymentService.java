package com.zhiyu.health.service;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Payment;
import com.zhiyu.health.mapper.PaymentMapper;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final Contracts contracts;

    public void createUnpaid(long appointmentId, BigDecimal amount) {
        Payment payment = new Payment();
        payment.setAppointmentId(appointmentId);
        payment.setAmount(amount);
        payment.setStatus(contracts.paymentFlow().statuses().get("unpaid"));
        paymentMapper.insertUnpaid(payment);
    }
}
