package com.zhiyu.health.service.consultation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.consultation.ConsultationRecord;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.ConsultationRecordMapper;
import com.zhiyu.health.mapper.consultation.ReceptionMapper;
import com.zhiyu.health.mapper.health.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.appointment.AppointmentService;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.scheduling.SlotWindowGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ReceptionService {

    private final StaffUserMapper staffUserMapper;
    private final ReceptionMapper receptionMapper;
    private final ConsultationRecordMapper consultationRecordMapper;
    private final InAppMessageMapper messageMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper prescriptionItemMapper;
    private final HealthProfileAllergyMapper allergyMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final AgentClient agentClient;
    private final DisclaimerService disclaimers;
    private final Contracts contracts;
    private final ObjectMapper objectMapper;
    private final AppointmentService appointments;
    private final SlotWindowGuard slotWindowGuard;

    public ReceptionDashboard today(long staffId) {
        long doctorId = requireDoctor(staffId);
        // 接诊台入口先全局惰性收敛：过期待支付单（释放号源）+ 过点未叫号已支付单（取消+退款+消息，票 92）
        // + 过点就诊中单（自动转已接诊，票 94，释放单叫号约束），再查可见列表：
        // 收敛是全局副作用，可见性是医生视角过滤，两者解耦（ADR-0033）。
        appointments.expireOverdueAppointments();
        appointments.expireUncalledAppointments();
        appointments.expireUnfinishedConsultations();
        LocalDate today = LocalDate.now();
        List<ScheduleView> schedules = receptionMapper.selectSchedules(doctorId, today).stream()
                .map(this::toScheduleView)
                .toList();
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        List<AppointmentView> appointmentsList = receptionMapper
                .selectAppointments(
                        doctorId, today, flow.status("booked"), flow.status("in_progress"), flow.status("visited"))
                .stream()
                .map(this::toAppointmentView)
                .toList();
        return new ReceptionDashboard(today.toString(), schedules, appointmentsList);
    }

    public AppointmentDetail detail(long staffId, long appointmentId) {
        long doctorId = requireDoctor(staffId);
        Appointment appointment = receptionMapper.selectAppointment(appointmentId, doctorId);
        if (appointment == null) {
            throw new ApiException(404, "挂号单不存在");
        }
        ConsultationRecord record = consultationRecordMapper.selectByAppointmentId(appointmentId);
        return new AppointmentDetail(
                toAppointmentView(appointment),
                record == null ? null : record.getDiagnosis(),
                record == null ? null : record.getAdvice(),
                record == null || record.getCreatedAt() == null
                        ? null
                        : record.getCreatedAt().toString(),
                patientProfileOf(appointment),
                prescriptionDetailOf(appointmentId));
    }

    /** 接诊详情患者健康档案信息（票 94）：取挂号时固化的 health_profile_id（非当前活跃档案），过敏史空返回空列表。 */
    private PatientProfile patientProfileOf(Appointment appointment) {
        Long profileId = appointment.getHealthProfileId();
        if (profileId == null) {
            return null;
        }
        List<String> allergies = allergyMapper.selectAllergens(profileId);
        Integer age = appointment.getBirthDate() == null
                ? null
                : Period.between(appointment.getBirthDate(), LocalDate.now(clock))
                        .getYears();
        return new PatientProfile(appointment.getGender(), age, allergies);
    }

    /** 接诊详情处方明细（票 94）：无处方返回 null；有处方带出药品列表 + 状态 + 驳回原因。 */
    private PrescriptionDetail prescriptionDetailOf(long appointmentId) {
        Prescription prescription = prescriptionMapper.selectByAppointmentId(appointmentId);
        if (prescription == null) {
            return null;
        }
        List<PrescriptionItemView> itemViews = prescriptionItemMapper.selectDetailed(prescription.getId()).stream()
                .map(item -> new PrescriptionItemView(
                        item.getMedicationName(),
                        item.getSpecification(),
                        item.getDosage(),
                        item.getFrequency(),
                        item.getDuration(),
                        item.getQuantity(),
                        item.getNotes()))
                .toList();
        return new PrescriptionDetail(prescription.getStatus(), prescription.getReviewReason(), itemViews);
    }

    public AppointmentDetail complete(long staffId, long appointmentId, String diagnosis, String advice) {
        long doctorId = requireDoctor(staffId);
        String normalizedDiagnosis = diagnosis.trim();
        String normalizedAdvice = advice.trim();
        Appointment preview = receptionMapper.selectAppointment(appointmentId, doctorId);
        if (preview == null) {
            throw new ApiException(404, "挂号单不存在");
        }
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        String visitedStatus = flow.status("visited");
        if (visitedStatus.equals(preview.getStatus())) {
            return detail(staffId, appointmentId);
        }
        Contracts.AppointmentFlow.Transition complete = flow.transitions().get("complete");
        if (!complete.allows(preview.getStatus())) {
            throw new ApiException(409, "当前状态不可接诊");
        }
        // 模型调用放在事务外，且只传医生填写的两项内容，避免带入病情摘要或其他患者原文。
        AgentClient.ClinicalResponse summary = agentClient.summarizeConsultation(normalizedDiagnosis, normalizedAdvice);
        transactionTemplate.executeWithoutResult(status -> {
            // 挂号单行锁将诊断落库与状态流转绑定为一个事务，避免重复接诊生成两份记录。
            Appointment appointment = receptionMapper.selectAppointmentForUpdate(appointmentId, doctorId);
            if (appointment == null) {
                throw new ApiException(404, "挂号单不存在");
            }
            if (visitedStatus.equals(appointment.getStatus())) {
                return;
            }
            if (!complete.allows(appointment.getStatus())) {
                throw new ApiException(409, "当前状态不可接诊");
            }
            ConsultationRecord record = new ConsultationRecord();
            record.setAppointmentId(appointmentId);
            record.setDoctorId(doctorId);
            record.setDiagnosis(normalizedDiagnosis);
            record.setAdvice(normalizedAdvice);
            consultationRecordMapper.insert(record);
            InAppMessage message = new InAppMessage();
            message.setPatientId(appointment.getPatientId());
            message.setType(contracts.prescriptionFlow().messageTypes().get("consultation_summary"));
            message.setTitle("就诊小结");
            message.setContent(summary.content());
            // server-java 出口不信任模型返回的免责声明字段，始终以统一契约兜底。
            message.setDisclaimer(disclaimers.text());
            message.setRelatedAppointmentId(appointmentId);
            messageMapper.insert(message);
            if (receptionMapper.markVisited(appointmentId, complete.from().get(0), complete.to()) != 1) {
                throw new IllegalStateException("接诊状态流转失败");
            }
        });
        return detail(staffId, appointmentId);
    }

    /** 医生叫号：仅本人排班下的已约挂号可推进，状态与站内通知同事务提交。 */
    public AppointmentDetail call(long staffId, long appointmentId) {
        long doctorId = requireDoctor(staffId);
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        String inProgressStatus = flow.status("in_progress");
        Appointment preview = receptionMapper.selectAppointment(appointmentId, doctorId);
        if (preview == null) {
            throw new ApiException(404, "挂号单不存在");
        }
        if (inProgressStatus.equals(preview.getStatus())) {
            return detail(staffId, appointmentId);
        }
        Contracts.AppointmentFlow.Transition call = flow.transitions().get("call");
        if (!call.allows(preview.getStatus())) {
            throw new ApiException(409, "当前状态不可叫号");
        }
        // 叫号时段窗口（票 87）：只拦叫号，完成接诊与查看不拦；
        // 仅当前时间处于该挂号所属排班有效时段窗口内才可叫号，过点滞留患者不可叫号。
        String timeSlotValue =
                preview.getTimeSlot() == null ? null : preview.getTimeSlot().getValue();
        if (!slotWindowGuard.isWithinWindow(preview.getScheduleDate(), timeSlotValue)) {
            throw new ApiException(409, "当前不在该挂号所属出诊时段内，暂不可叫号");
        }
        transactionTemplate.executeWithoutResult(status -> {
            // 行锁串行化重复叫号；状态推进与通知写入同事务，任一失败都不留下半完成结果。
            Appointment appointment = receptionMapper.selectAppointmentForUpdate(appointmentId, doctorId);
            if (appointment == null) {
                throw new ApiException(404, "挂号单不存在");
            }
            if (inProgressStatus.equals(appointment.getStatus())) {
                return;
            }
            if (!call.allows(appointment.getStatus())) {
                throw new ApiException(409, "当前状态不可叫号");
            }
            // 单叫号约束（票 81，ADR-0033）：医生维度同时只能一条就诊中，
            // 既有 IN_PROGRESS 时必须先完成接诊（推进为已接诊）才能叫下一个号。
            if (receptionMapper.selectInProgressForDoctor(doctorId, inProgressStatus) != null) {
                throw new ApiException(409, "请先完成当前就诊后再叫下一个号");
            }
            writeCalledNotice(appointment);
            if (receptionMapper.markInProgress(appointmentId, call.from().get(0), call.to()) != 1) {
                throw new IllegalStateException("叫号状态流转失败");
            }
        });
        return detail(staffId, appointmentId);
    }

    private void writeCalledNotice(Appointment appointment) {
        Contracts.AppointmentFlow.CalledNotice notice =
                contracts.appointmentFlow().calledNotice();
        var content = new LinkedHashMap<String, Object>();
        content.put("greeting", notice.greeting());
        content.put("room", appointment.getRoom());
        content.put("sequence_number", appointment.getSequenceNumber());
        content.put(
                "schedule_date",
                appointment.getScheduleDate() == null
                        ? null
                        : appointment.getScheduleDate().toString());
        content.put(
                "time_slot",
                appointment.getTimeSlot() == null
                        ? null
                        : appointment.getTimeSlot().getValue());
        InAppMessage message = new InAppMessage();
        message.setPatientId(appointment.getPatientId());
        message.setType(notice.messageType());
        message.setTitle(notice.title());
        try {
            message.setContent(objectMapper.writeValueAsString(content));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("叫号通知 content 序列化失败", exception);
        }
        message.setDisclaimer(disclaimers.text());
        message.setRelatedAppointmentId(appointment.getId());
        messageMapper.insert(message);
    }

    private long requireDoctor(long staffId) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole()) || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        return staff.getDoctorId();
    }

    private ScheduleView toScheduleView(Schedule schedule) {
        String status;
        if (!Boolean.TRUE.equals(schedule.getIsActive())) {
            status = "INACTIVE";
        } else if (schedule.getRemainingSlots() != null && schedule.getRemainingSlots() <= 0) {
            status = "FULL";
        } else {
            status = "AVAILABLE";
        }
        // 该排班当前是否处于有效时段窗口（票 87）：供前端排班卡片整卡置灰 + 副标题提示，
        // 与挂号队列的 callable 同源（slotWindowGuard.isWithinWindow），避免前端复制时段表。
        String timeSlotValue =
                schedule.getTimeSlot() == null ? null : schedule.getTimeSlot().getValue();
        boolean inWindow = slotWindowGuard.isWithinWindow(schedule.getScheduleDate(), timeSlotValue);
        return new ScheduleView(
                schedule.getId(),
                schedule.getTimeSlot().getValue(),
                schedule.getTotalSlots(),
                schedule.getRemainingSlots(),
                Boolean.TRUE.equals(schedule.getIsActive()),
                status,
                inWindow);
    }

    private AppointmentView toAppointmentView(Appointment appointment) {
        // 挂号状态与处方状态并列：有处方时前端优先展示处方状态（待审核/已通过/已驳回）。
        // 同时回传驳回原因，使接诊抽屉能像在线问诊一样展示审核结果，而不必新增查询端点。
        Prescription prescription = prescriptionMapper.selectByAppointmentId(appointment.getId());
        String prescriptionStatus = prescription == null ? null : prescription.getStatus();
        String prescriptionReviewReason = prescription == null ? null : prescription.getReviewReason();
        String timeSlotValue = appointment.getTimeSlot() == null
                ? null
                : appointment.getTimeSlot().getValue();
        // 每行是否可叫号（票 87）：仅待就诊且当前处于有效时段窗口（含起止闭区间）可叫，
        // 后端统一判定，前端不自行复制时段表。
        boolean callable = contracts.appointmentFlow().status("booked").equals(appointment.getStatus())
                && slotWindowGuard.isWithinWindow(appointment.getScheduleDate(), timeSlotValue);
        return new AppointmentView(
                appointment.getId(),
                appointment.getScheduleId(),
                appointment.getPatientNickname(),
                appointment.getSequenceNumber(),
                appointment.getStatus(),
                contracts.appointmentFlow().statusLabel(appointment.getStatus()),
                prescriptionStatus,
                prescriptionReviewReason,
                appointment.getScheduleDate() == null
                        ? null
                        : appointment.getScheduleDate().toString(),
                timeSlotValue,
                // 库存纯内容直接透传；免责声明维持接诊台既有语义：独立字段恒挂载。
                appointment.getConditionSummary(),
                disclaimers.text(),
                callable);
    }

    public record ReceptionDashboard(String date, List<ScheduleView> schedules, List<AppointmentView> appointments) {}

    public record ScheduleView(
            Long id,
            String timeSlot,
            Integer totalSlots,
            Integer remainingSlots,
            boolean active,
            String status,
            boolean inWindow) {}

    public record AppointmentView(
            Long id,
            Long scheduleId,
            String patientNickname,
            Integer sequenceNumber,
            String statusCode,
            String status,
            String prescriptionStatus,
            @JsonProperty("prescription_review_reason") String prescriptionReviewReason,
            String scheduleDate,
            String timeSlot,
            String conditionSummary,
            String summaryDisclaimer,
            boolean callable) {}

    public record AppointmentDetail(
            AppointmentView appointment,
            String diagnosis,
            String advice,
            String completedAt,
            PatientProfile patientProfile,
            PrescriptionDetail prescription) {}

    /** 接诊详情患者健康档案（票 94）：性别/年龄/过敏史，过敏史空列表由前端显示"未填"。 */
    public record PatientProfile(String gender, Integer age, List<String> allergies) {}

    /** 接诊详情处方明细（票 94）：状态 + 驳回原因 + 药品列表；无处方时整体为 null。 */
    public record PrescriptionDetail(
            String status, @JsonProperty("review_reason") String reviewReason, List<PrescriptionItemView> items) {}

    public record PrescriptionItemView(
            String name,
            String specification,
            String dosage,
            String frequency,
            String duration,
            Integer quantity,
            String notes) {}
}
