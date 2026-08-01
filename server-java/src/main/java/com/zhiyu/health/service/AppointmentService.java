package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import com.zhiyu.health.service.mapping.AppointmentDtoMapper;
import java.math.BigDecimal;
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
    private final HealthProfileService healthProfiles;
    private final PaymentService payments;
    private final Contracts contracts;
    private final AppointmentDtoMapper appointmentDtos;

    public AppointmentView create(long patientId, long conversationId, long scheduleId) {
        CreatedAppointment created = reserve(patientId, conversationId, scheduleId, DuplicatePolicy.RETURN_EXISTING);
        try {
            // 挂号与号源事务已经提交；收费附属记录失败不得撤销真实挂号结果。
            payments.createUnpaid(created.id(), created.registrationFee());
        } catch (RuntimeException ignored) {
            // 后续幂等挂号请求会再次尝试补建收费记录，唯一键避免重复收费。
        }
        return view(created.id());
    }

    /** C 端功能目录直接挂号；按票 41 边界不创建收费记录，重复提交返回明确冲突。 */
    public AppointmentView createDirect(long patientId, long scheduleId) {
        return view(reserve(patientId, null, scheduleId, DuplicatePolicy.REJECT).id());
    }

    private CreatedAppointment reserve(
            long patientId, Long conversationId, long scheduleId, DuplicatePolicy duplicatePolicy) {
        long profileId = healthProfiles.requireActive(patientId).getId();
        // withDeduction 的补偿范围覆盖整个事务（含提交失败）：已预扣未提交即回补 Redis。
        CreatedAppointment created = slotAccounting.withDeduction(
                scheduleId,
                deduction -> transactionTemplate.execute(status -> {
                    // 排班行锁把幂等判断、序号分配与 PG 对账串成一个临界区，防止并发重复扣减或重号。
                    Schedule schedule = scheduleMapper.selectByIdForUpdate(scheduleId);
                    if (schedule == null || !Boolean.TRUE.equals(schedule.getIsActive())) {
                        throw new ApiException(404, "排班不存在或已停用");
                    }
                    Appointment existing =
                            appointmentMapper.selectForProfileAndSchedule(patientId, profileId, scheduleId);
                    if (existing != null) {
                        if (duplicatePolicy == DuplicatePolicy.REJECT) {
                            throw new ApiException(409, "请勿重复挂号");
                        }
                        return new CreatedAppointment(existing.getId(), existing.getRegistrationFee());
                    }
                    // 幂等检查通过后才预扣；售罄在此处抛 409 且 Redis 已被 SlotAccounting 回补。
                    deduction.acquire();
                    if (scheduleMapper.decrementRemainingSlots(scheduleId) != 1) {
                        throw new ApiException(409, "号源已约满");
                    }
                    Appointment appointment = new Appointment();
                    appointment.setPatientId(patientId);
                    appointment.setHealthProfileId(profileId);
                    appointment.setConversationId(conversationId);
                    appointment.setScheduleId(scheduleId);
                    appointment.setSequenceNumber(appointmentMapper.nextSequenceNumber(scheduleId));
                    appointment.setRegistrationFee(schedule.getRegistrationFee());
                    appointment.setStatus(Appointment.STATUS_BOOKED);
                    appointmentMapper.insert(appointment);
                    return new CreatedAppointment(appointment.getId(), appointment.getRegistrationFee());
                }));
        return created;
    }

    public List<AppointmentView> listForPatient(long patientId) {
        long profileId = healthProfiles.requireActive(patientId).getId();
        return appointmentMapper.selectViewsByProfile(patientId, profileId).stream()
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
        long profileId = healthProfiles.requireActive(patientId).getId();
        // 摘要只存纯内容；免责声明在响应装配时由 DisclaimerService 挂载，不入库。
        if (appointmentMapper.updateConditionSummary(
                        appointmentId, patientId, profileId, conversationId, summary.trim())
                != 1) {
            throw new ApiException(404, "挂号单不存在");
        }
        return view(appointmentId);
    }

    public AppointmentView cancel(long patientId, long appointmentId) {
        long profileId = healthProfiles.requireActive(patientId).getId();
        // withRefund 的补偿范围覆盖整个事务（含提交失败）：已退还未提交即撤销退还。
        Long resultId = slotAccounting.withRefund(refund -> transactionTemplate.execute(status -> {
            // 挂号单行锁保证重复取消只让首次状态转换进入双存储回补分支。
            Appointment appointment = appointmentMapper.selectByIdForUpdate(appointmentId, patientId, profileId);
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

    private AppointmentView toView(Appointment appointment) {
        String paymentStatus = appointment.getPaymentStatus();
        String paymentStatusLabel = paymentStatus == null
                ? null
                : contracts.paymentFlow().statusLabels().get(paymentStatus);
        return appointmentDtos.toView(
                appointment, Appointment.displayStatus(appointment.getStatus()), paymentStatusLabel);
    }

    public boolean isPaymentPayable(String paymentStatus) {
        return contracts.paymentFlow().statuses().get("unpaid").equals(paymentStatus);
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
            BigDecimal registrationFee,
            String paymentStatus,
            String paymentStatusLabel,
            String conditionSummary,
            String createdAt) {}

    private record CreatedAppointment(Long id, BigDecimal registrationFee) {}

    private enum DuplicatePolicy {
        RETURN_EXISTING,
        REJECT
    }
}
