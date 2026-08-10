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

/**
 * C 端小程序"我的挂号"接口层。
 * <p>
 * 职责边界：只做患者身份装配与请求参数校验，所有业务逻辑下沉到 {@link AppointmentService}。
 * 不直接操作号源、不判断支付状态机、不自行计算可取消性——这些一律由 service 层收口。
 * </p>
 */
@RestController
@RequestMapping("/api/c/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final DisclaimerService disclaimers;
    private final AppointmentCardMapper appointmentCards;

    /**
     * 查询当前患者的挂号列表。
     *
     * @param patientId 从 JWT 解析出的患者身份标识（由 {@link AuthFilter} 注入）
     * @return 挂号卡片列表，按创建时间倒序
     */
    @GetMapping
    public List<AppointmentOut> list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return appointmentService.listForPatient(patientId).stream()
                .map(this::toOut)
                .toList();
    }

    /**
     * 小程序直接挂号：患者从功能目录选择排班后直接下单。
     * <p>
     * 与 AI 导诊链路挂号的区别由 {@link AppointmentService#createDirect} 内部分流
     * （通过 {@code DuplicatePolicy.REJECT} 拒绝重复挂号），controller 只透传排班 ID。
     * </p>
     *
     * @param patientId 当前登录患者 ID
     * @param request   包含目标排班 ID 的请求体
     * @return 创建成功的挂号卡片视图
     */
    @PostMapping
    public AppointmentOut create(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @Valid @RequestBody CreateAppointmentRequest request) {
        return toOut(appointmentService.createDirect(patientId, request.scheduleId()));
    }

    /**
     * 患者主动取消挂号。
     * <p>
     * 取消规则由 service 层根据状态机与取消时间窗口计算（票 90）：
     * <ul>
     *   <li>待支付（PENDING_PAYMENT）：随时可取消，释放号源</li>
     *   <li>已支付（BOOKED）：距就诊开始不足 {@code cancelCutoffMinutes} 分钟不可取消</li>
     *   <li>其他状态（就诊中/已取消/已接诊）：不可取消</li>
     * </ul>
     * </p>
     *
     * @param patientId     当前登录患者 ID
     * @param appointmentId 要取消的挂号单 ID
     * @return 取消后的挂号卡片视图
     */
    @PostMapping("/{appointmentId}/cancel")
    public AppointmentOut cancel(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long appointmentId) {
        return toOut(appointmentService.cancel(patientId, appointmentId));
    }

    /**
     * 挂号卡片视图装配：将 service 层的 {@link AppointmentService.AppointmentView} 转换为 C 端输出格式。
     * <p>
     * 同时挂载三样附加信息：
     * <ol>
     *   <li>病情摘要免责声明——经 {@link DisclaimerService} 从契约注入，controller 不信任上游</li>
     *   <li>{@code payment_payable} 标志——待支付且未超时才允许发起支付</li>
     *   <li>{@code cancellable}——由 service 层根据状态机与取消时间窗口计算后透传（票 90）</li>
     * </ol>
     * </p>
     */
    private AppointmentOut toOut(AppointmentService.AppointmentView value) {
        return appointmentCards.toPatientOut(
                value,
                disclaimers.mountIfPresent(value.conditionSummary()),
                appointmentService.isPaymentPayable(value.paymentStatus(), value.statusCode()),
                value.cancellable());
    }

    /**
     * C 端挂号单卡片输出 DTO。
     * <p>
     * 字段命名统一使用 snake_case（配合 {@link JsonProperty}），与小程序前端约定保持一致。
     * 包含就诊信息、支付状态、取消权限、就诊指引等完整视图。
     * </p>
     */
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
            @JsonProperty("cancellable") boolean cancellable,
            @JsonProperty("condition_summary") String conditionSummary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            @JsonProperty("hospital_name") String hospitalName,
            @JsonProperty("campus_name") String campusName,
            @JsonProperty("campus_address") String campusAddress,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("payment_deadline") String paymentDeadline) {}

    /**
     * 创建挂号请求 DTO。
     * <p>
     * 小程序直接挂号时只需传入排班 ID，患者身份从 JWT 解析，无需前端传递。
     * </p>
     */
    public record CreateAppointmentRequest(@JsonProperty("schedule_id") @NotNull @Positive Long scheduleId) {}
}
