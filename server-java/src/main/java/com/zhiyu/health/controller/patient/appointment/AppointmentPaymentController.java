package com.zhiyu.health.controller.patient.appointment;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.appointment.AppointmentService;
import com.zhiyu.health.service.appointment.PaymentService;
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
    private final AppointmentService appointments;

    @PostMapping("/{appointmentId}/payment/pay")
    public PaymentService.PaymentView pay(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long appointmentId) {
        // 支付入口惰性收敛：若该单已超时，先收敛为已取消并释放号源，
        // 再进入支付状态机--收敛后 payment 仍为 UNPAID，但挂号单已 CANCELLED，
        // markBooked 的 CAS 会拒绝推进，payForPatient 抛 409 提示状态变化。
        appointments.expireOverdueAppointments();
        return service.payForPatient(patientId, appointmentId);
    }
}
