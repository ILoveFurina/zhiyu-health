package com.zhiyu.health.controller.c;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端挂号收费接口，只装配已鉴权患者身份，支付状态机由 service 执行。 */
@RestController
@RequestMapping("/api/c/appointments")
@RequiredArgsConstructor
public class AppointmentPaymentController {

    private final PaymentService service;

    @PostMapping("/{appointmentId}/payment/pay")
    public PaymentService.PaymentView pay(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long appointmentId) {
        return service.payForPatient(patientId, appointmentId);
    }
}
