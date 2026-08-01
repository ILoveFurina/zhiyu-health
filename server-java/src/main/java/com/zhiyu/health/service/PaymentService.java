package com.zhiyu.health.service;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.mapper.PaymentMapper;
import com.zhiyu.health.service.mapping.PaymentDtoMapper;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final Contracts contracts;
    private final PaymentDtoMapper paymentDtos;

    public void createUnpaid(long appointmentId, BigDecimal amount) {
        paymentMapper.insertUnpaid(paymentDtos.toUnpaid(
                appointmentId, amount, contracts.paymentFlow().statuses().get("unpaid")));
    }
}
