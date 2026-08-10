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

/**
 * C 端小程序挂号支付接口层。
 * <p>
 * 职责边界：只装配已鉴权患者身份，支付状态机与号源管理统一由 {@link PaymentService} 与
 * {@link AppointmentService} 执行。controller 不介入支付金额计算、不操作号源。
 * </p>
 */
@RestController
@RequestMapping("/api/c/appointments")
@RequiredArgsConstructor
public class AppointmentPaymentController {

    private final PaymentService service;
    private final AppointmentService appointments;

    /**
     * 患者发起挂号费支付。
     * <p>
     * 支付前执行惰性收敛：若该挂号单已超出支付截止时间，先将其收敛为已取消并释放号源，
     * 再进入支付状态机。收敛后 payment 行仍为 UNPAID，但挂号单状态已变为 CANCELLED，
     * 后续 {@code markBooked} 的 CAS 守卫会拒绝推进，{@code payForPatient} 抛 409 提示状态变化。
     * </p>
     *
     * @param patientId     当前登录患者 ID（JWT 解析）
     * @param appointmentId 要支付的挂号单 ID
     * @return 支付结果视图
     */
    @PostMapping("/{appointmentId}/payment/pay")
    public PaymentService.PaymentView pay(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long appointmentId) {
        appointments.expireOverdueAppointments();
        return service.payForPatient(patientId, appointmentId);
    }
}
