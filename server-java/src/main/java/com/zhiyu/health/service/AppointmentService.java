package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final ScheduleMapper scheduleMapper;
    private final SlotAccounting slotAccounting;
    private final TransactionTemplate transactionTemplate;

    public AppointmentView create(long patientId, long conversationId, long scheduleId) {
        // withDeduction 的补偿范围覆盖整个事务（含提交失败）：已预扣未提交即回补 Redis。
        Long appointmentId = slotAccounting.withDeduction(
                scheduleId,
                deduction -> transactionTemplate.execute(status -> {
                    // 排班行锁把幂等判断、序号分配与 PG 对账串成一个临界区，防止并发重复扣减或重号。
                    Schedule schedule = scheduleMapper.selectByIdForUpdate(scheduleId);
                    if (schedule == null || !Boolean.TRUE.equals(schedule.getIsActive())) {
                        throw new ApiException(404, "排班不存在或已停用");
                    }
                    Appointment existing = appointmentMapper.selectForPatientAndSchedule(patientId, scheduleId);
                    if (existing != null) {
                        return existing.getId();
                    }
                    // 幂等检查通过后才预扣；售罄在此处抛 409 且 Redis 已被 SlotAccounting 回补。
                    deduction.acquire();
                    if (scheduleMapper.decrementRemainingSlots(scheduleId) != 1) {
                        throw new ApiException(409, "号源已约满");
                    }
                    Appointment appointment = new Appointment();
                    appointment.setPatientId(patientId);
                    appointment.setConversationId(conversationId);
                    appointment.setScheduleId(scheduleId);
                    appointment.setSequenceNumber(appointmentMapper.nextSequenceNumber(scheduleId));
                    appointment.setStatus(Appointment.STATUS_BOOKED);
                    appointmentMapper.insert(appointment);
                    return appointment.getId();
                }));
        return view(appointmentId);
    }

    public List<AppointmentView> listForPatient(long patientId) {
        return appointmentMapper.selectViewsByPatient(patientId).stream()
                .map(this::toView)
                .toList();
    }

    public AppointmentView createWithSummary(long patientId, long conversationId, long scheduleId, String summary) {
        AppointmentView created = create(patientId, conversationId, scheduleId);
        if (created.conditionSummary() != null) {
            // 幂等重试返回已有完整挂号单，保留原会话摘要，禁止被新会话覆盖或误报为发送失败。
            return created;
        }
        try {
            return saveConditionSummary(patientId, conversationId, created.id(), summary);
        } catch (RuntimeException exception) {
            // 创建事务已经独立提交；摘要失败只降级卡片，不回滚或隐藏真实挂号结果。
            return created;
        }
    }

    public AppointmentView saveConditionSummary(
            long patientId, long conversationId, long appointmentId, String summary) {
        String safeSummary = ensureDisclaimer(summary);
        if (appointmentMapper.updateConditionSummary(appointmentId, patientId, conversationId, safeSummary) != 1) {
            throw new ApiException(404, "挂号单不存在");
        }
        return view(appointmentId);
    }

    public AppointmentView cancel(long patientId, long appointmentId) {
        // withRefund 的补偿范围覆盖整个事务（含提交失败）：已退还未提交即撤销退还。
        Long resultId = slotAccounting.withRefund(refund -> transactionTemplate.execute(status -> {
            // 挂号单行锁保证重复取消只让首次状态转换进入双存储回补分支。
            Appointment appointment = appointmentMapper.selectByIdForUpdate(appointmentId, patientId);
            if (appointment == null) {
                throw new ApiException(404, "挂号单不存在");
            }
            if (Appointment.STATUS_CANCELLED.equals(appointment.getStatus())) {
                return appointment.getId();
            }
            if (!Appointment.STATUS_BOOKED.equals(appointment.getStatus())) {
                throw new ApiException(409, "当前状态不可取消");
            }
            if (appointmentMapper.markCancelled(appointmentId) != 1
                    || scheduleMapper.incrementRemainingSlots(appointment.getScheduleId()) != 1) {
                throw new IllegalStateException("取消挂号的 PostgreSQL 回补失败");
            }
            refund.grant(appointment.getScheduleId());
            return appointment.getId();
        }));
        return view(resultId);
    }

    private AppointmentView view(Long appointmentId) {
        if (appointmentId == null) {
            throw new IllegalStateException("挂号事务未返回挂号单");
        }
        Appointment appointment = appointmentMapper.selectViewById(appointmentId);
        if (appointment == null) {
            throw new IllegalStateException("挂号单写入后不可见");
        }
        return toView(appointment);
    }

    private String ensureDisclaimer(String summary) {
        String trimmed = summary.trim();
        if (trimmed.contains(ChatService.DISCLAIMER)) {
            return trimmed;
        }
        String separator =
                trimmed.endsWith("。") || trimmed.endsWith("！") || trimmed.endsWith("？") || trimmed.endsWith(".")
                        ? ""
                        : "。";
        return trimmed + separator + ChatService.DISCLAIMER;
    }

    private AppointmentView toView(Appointment appointment) {
        return new AppointmentView(
                appointment.getId(),
                appointment.getScheduleId(),
                appointment.getDoctorId(),
                appointment.getDoctorName(),
                appointment.getDepartmentName(),
                appointment.getScheduleDate() == null
                        ? null
                        : appointment.getScheduleDate().toString(),
                appointment.getTimeSlot() == null
                        ? null
                        : appointment.getTimeSlot().getValue(),
                appointment.getSequenceNumber(),
                Appointment.displayStatus(appointment.getStatus()),
                summaryWithoutDisclaimer(appointment.getConditionSummary()),
                appointment.getCreatedAt() == null
                        ? null
                        : appointment.getCreatedAt().toString());
    }

    private String summaryWithoutDisclaimer(String summary) {
        if (summary == null) {
            return null;
        }
        String content = summary.replace(ChatService.DISCLAIMER, "").trim();
        return content.endsWith("。") ? content.substring(0, content.length() - 1) : content;
    }

    public record AppointmentView(
            Long id,
            Long scheduleId,
            Long doctorId,
            String doctorName,
            String departmentName,
            String scheduleDate,
            String timeSlot,
            Integer sequenceNumber,
            String status,
            String conditionSummary,
            String createdAt) {}
}
