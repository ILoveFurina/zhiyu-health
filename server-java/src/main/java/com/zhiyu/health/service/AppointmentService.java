package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final ScheduleMapper scheduleMapper;
    private final SlotCounter slotCounter;
    private final TransactionTemplate transactionTemplate;

    public AppointmentService(AppointmentMapper appointmentMapper, ScheduleMapper scheduleMapper,
                              SlotCounter slotCounter, TransactionTemplate transactionTemplate) {
        this.appointmentMapper = appointmentMapper;
        this.scheduleMapper = scheduleMapper;
        this.slotCounter = slotCounter;
        this.transactionTemplate = transactionTemplate;
    }

    public AppointmentView create(long patientId, long conversationId, long scheduleId) {
        AtomicBoolean redisDeducted = new AtomicBoolean();
        Long appointmentId;
        try {
            appointmentId = transactionTemplate.execute(status -> {
                // 排班行锁把幂等判断、序号分配与 PG 对账串成一个临界区，防止并发重复扣减或重号。
                Schedule schedule = scheduleMapper.selectByIdForUpdate(scheduleId);
                if (schedule == null || !Boolean.TRUE.equals(schedule.getIsActive())) {
                    throw new ApiException(404, "排班不存在或已停用");
                }
                Appointment existing = appointmentMapper.selectForPatientAndSchedule(
                        patientId, scheduleId);
                if (existing != null) {
                    return existing.getId();
                }
                long redisRemaining = slotCounter.decrement(scheduleId);
                if (redisRemaining < 0) {
                    slotCounter.increment(scheduleId);
                    throw new ApiException(409, "号源已约满");
                }
                redisDeducted.set(true);
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
            });
        } catch (RuntimeException exception) {
            if (redisDeducted.get()) {
                // Redis 不参与 PG 事务；PG 回滚或提交失败时只回补本次已经成功的预扣。
                slotCounter.increment(scheduleId);
            }
            throw exception;
        }
        return view(appointmentId);
    }

    public List<AppointmentView> listForPatient(long patientId) {
        return appointmentMapper.selectViewsByPatient(patientId).stream().map(this::toView).toList();
    }

    public AppointmentView createWithSummary(long patientId, long conversationId,
                                             long scheduleId, String summary) {
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

    public AppointmentView saveConditionSummary(long patientId, long conversationId,
                                                long appointmentId, String summary) {
        String safeSummary = ensureDisclaimer(summary);
        if (appointmentMapper.updateConditionSummary(
                appointmentId, patientId, conversationId, safeSummary) != 1) {
            throw new ApiException(404, "挂号单不存在");
        }
        return view(appointmentId);
    }

    public AppointmentView cancel(long patientId, long appointmentId) {
        AtomicBoolean redisRefunded = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong refundedScheduleId =
                new java.util.concurrent.atomic.AtomicLong();
        Long resultId;
        try {
            resultId = transactionTemplate.execute(status -> {
                // 挂号单行锁保证重复取消只让首次状态转换进入双存储回补分支。
                Appointment appointment = appointmentMapper.selectByIdForUpdate(
                        appointmentId, patientId);
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
                slotCounter.increment(appointment.getScheduleId());
                refundedScheduleId.set(appointment.getScheduleId());
                redisRefunded.set(true);
                return appointment.getId();
            });
        } catch (RuntimeException exception) {
            if (redisRefunded.get()) {
                // 提交失败时撤销 Redis 回补，避免 PG 已回滚而号源池被重复增加。
                slotCounter.decrement(refundedScheduleId.get());
            }
            throw exception;
        }
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
        String separator = trimmed.endsWith("。") || trimmed.endsWith("！")
                || trimmed.endsWith("？") || trimmed.endsWith(".") ? "" : "。";
        return trimmed + separator + ChatService.DISCLAIMER;
    }

    private AppointmentView toView(Appointment appointment) {
        String displayStatus = Appointment.STATUS_BOOKED.equals(appointment.getStatus())
                ? "已约" : "已取消";
        return new AppointmentView(
                appointment.getId(), appointment.getScheduleId(), appointment.getDoctorId(),
                appointment.getDoctorName(), appointment.getDepartmentName(),
                appointment.getScheduleDate() == null ? null : appointment.getScheduleDate().toString(),
                appointment.getTimeSlot() == null ? null : appointment.getTimeSlot().getValue(),
                appointment.getSequenceNumber(), displayStatus,
                summaryWithoutDisclaimer(appointment.getConditionSummary()),
                appointment.getCreatedAt() == null ? null : appointment.getCreatedAt().toString());
    }

    private String summaryWithoutDisclaimer(String summary) {
        if (summary == null) {
            return null;
        }
        String content = summary.replace(ChatService.DISCLAIMER, "").trim();
        return content.endsWith("。") ? content.substring(0, content.length() - 1) : content;
    }

    public record AppointmentView(
            Long id, Long scheduleId, Long doctorId, String doctorName, String departmentName,
            String scheduleDate, String timeSlot, Integer sequenceNumber, String status,
            String conditionSummary, String createdAt) {
    }
}
