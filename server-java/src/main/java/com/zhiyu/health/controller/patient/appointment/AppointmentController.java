package com.zhiyu.health.controller.patient.appointment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.patient.appointment.mapping.AppointmentCardMapper;
import com.zhiyu.health.service.appointment.AppointmentService;
import com.zhiyu.health.service.common.DisclaimerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端“我的挂号”接口，只做患者身份装配。 */
@RestController
@RequestMapping("/api/c/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final DisclaimerService disclaimers;
    private final AppointmentCardMapper appointmentCards;

    @GetMapping
    public List<AppointmentOut> list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return appointmentService.listForPatient(patientId).stream()
                .map(this::toOut)
                .toList();
    }

    @PostMapping
    public AppointmentOut create(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @Valid @RequestBody CreateAppointmentRequest request) {
        return toOut(appointmentService.createDirect(patientId, request.scheduleId()));
    }

    @PostMapping("/{appointmentId}/cancel")
    public AppointmentOut cancel(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long appointmentId) {
        return toOut(appointmentService.cancel(patientId, appointmentId));
    }

    private AppointmentOut toOut(AppointmentService.AppointmentView value) {
        return appointmentCards.toPatientOut(
                value,
                disclaimers.mountIfPresent(value.conditionSummary()),
                appointmentService.isPaymentPayable(value.paymentStatus()));
    }

    public record AppointmentOut(
            @JsonProperty("appointment_id") Long appointmentId,
            @JsonProperty("schedule_id") Long scheduleId,
            @JsonProperty("doctor_id") Long doctorId,
            @JsonProperty("doctor_name") String doctorName,
            @JsonProperty("department_name") String departmentName,
            @JsonProperty("schedule_date") String scheduleDate,
            @JsonProperty("time_slot") String timeSlot,
            @JsonProperty("sequence_number") Integer sequenceNumber,
            @JsonProperty("status_code") String statusCode,
            String status,
            @JsonProperty("registration_fee") BigDecimal registrationFee,
            @JsonProperty("payment_status") String paymentStatus,
            @JsonProperty("payment_status_label") String paymentStatusLabel,
            @JsonProperty("payment_payable") boolean paymentPayable,
            @JsonProperty("condition_summary") String conditionSummary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            @JsonProperty("hospital_name") String hospitalName,
            @JsonProperty("campus_name") String campusName,
            @JsonProperty("campus_address") String campusAddress,
            @JsonProperty("created_at") String createdAt) {}

    public record CreateAppointmentRequest(@JsonProperty("schedule_id") @NotNull @Positive Long scheduleId) {}
}
