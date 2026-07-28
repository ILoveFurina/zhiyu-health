package com.zhiyu.health.controller.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.service.AppointmentService;
import com.zhiyu.health.service.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Agent 层业务工具回调：仅校验可信运行时上下文与模型生成参数，再委托 service。 */
@Validated
@RestController
@RequestMapping("/api/agent/appointments")
public class AppointmentToolController {

    private final AppointmentService appointmentService;

    public AppointmentToolController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public AppointmentCard create(@Valid @RequestBody CreateAppointmentRequest request) {
        return AppointmentCard.from(appointmentService.createWithSummary(
                request.patientId(), request.conversationId(), request.scheduleId(),
                request.conditionSummary()));
    }

    @PostMapping("/{appointmentId}/summary")
    public AppointmentCard saveSummary(
            @PathVariable @Positive long appointmentId,
            @Valid @RequestBody SaveSummaryRequest request) {
        return AppointmentCard.from(appointmentService.saveConditionSummary(
                request.patientId(), request.conversationId(), appointmentId,
                request.conditionSummary()));
    }

    @GetMapping
    public AppointmentList getAppointments(@RequestParam("patient_id") @Positive long patientId) {
        return new AppointmentList(appointmentService.listForPatient(patientId).stream()
                .map(AppointmentCard::from)
                .toList());
    }

    public record CreateAppointmentRequest(
            @JsonProperty("patient_id") @NotNull @Positive Long patientId,
            @JsonProperty("conversation_id") @NotNull @Positive Long conversationId,
            @JsonProperty("schedule_id") @NotNull @Positive Long scheduleId,
            @JsonProperty("condition_summary") @NotBlank String conditionSummary) {
    }

    public record SaveSummaryRequest(
            @JsonProperty("patient_id") @NotNull @Positive Long patientId,
            @JsonProperty("conversation_id") @NotNull @Positive Long conversationId,
            @JsonProperty("condition_summary") @NotBlank String conditionSummary) {
    }

    public record AppointmentList(List<AppointmentCard> appointments) {
    }

    public record AppointmentCard(
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
            @JsonProperty("summary_sent") boolean summarySent,
            String notice) {

        static AppointmentCard from(AppointmentService.AppointmentView value) {
            boolean summarySent = value.conditionSummary() != null;
            return new AppointmentCard(
                    value.id(), value.scheduleId(), value.doctorId(), value.doctorName(),
                    value.departmentName(), value.scheduleDate(), value.timeSlot(),
                    value.sequenceNumber(), value.status(), value.conditionSummary(),
                    summarySent ? ChatService.DISCLAIMER : null,
                    summarySent, summarySent
                            ? "病情摘要已发送给医生"
                            : "挂号成功，病情摘要暂未发送");
        }
    }
}
