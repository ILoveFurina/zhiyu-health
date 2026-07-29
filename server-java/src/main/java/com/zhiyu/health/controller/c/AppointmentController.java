package com.zhiyu.health.controller.c;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.AppointmentService;
import com.zhiyu.health.service.ChatService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端“我的挂号”接口，只做患者身份装配。 */
@RestController
@RequestMapping("/api/c/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    @GetMapping
    public List<AppointmentOut> list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return appointmentService.listForPatient(patientId).stream()
                .map(AppointmentOut::from)
                .toList();
    }

    @PostMapping("/{appointmentId}/cancel")
    public AppointmentOut cancel(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long appointmentId) {
        return AppointmentOut.from(appointmentService.cancel(patientId, appointmentId));
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
            String status,
            @JsonProperty("condition_summary") String conditionSummary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            @JsonProperty("created_at") String createdAt) {

        static AppointmentOut from(AppointmentService.AppointmentView value) {
            return new AppointmentOut(
                    value.id(),
                    value.scheduleId(),
                    value.doctorId(),
                    value.doctorName(),
                    value.departmentName(),
                    value.scheduleDate(),
                    value.timeSlot(),
                    value.sequenceNumber(),
                    value.status(),
                    value.conditionSummary(),
                    value.conditionSummary() == null ? null : ChatService.DISCLAIMER,
                    value.createdAt());
        }
    }
}
