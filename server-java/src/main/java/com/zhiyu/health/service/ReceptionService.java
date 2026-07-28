package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.ConsultationRecord;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.ConsultationRecordMapper;
import com.zhiyu.health.mapper.ReceptionMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

@Service
public class ReceptionService {

    private final StaffUserMapper staffUserMapper;
    private final ReceptionMapper receptionMapper;
    private final ConsultationRecordMapper consultationRecordMapper;
    private final TransactionTemplate transactionTemplate;

    public ReceptionService(StaffUserMapper staffUserMapper, ReceptionMapper receptionMapper,
                            ConsultationRecordMapper consultationRecordMapper,
                            TransactionTemplate transactionTemplate) {
        this.staffUserMapper = staffUserMapper;
        this.receptionMapper = receptionMapper;
        this.consultationRecordMapper = consultationRecordMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ReceptionDashboard today(long staffId) {
        long doctorId = requireDoctor(staffId);
        LocalDate today = LocalDate.now();
        List<ScheduleView> schedules = receptionMapper.selectSchedules(doctorId, today).stream()
                .map(this::toScheduleView).toList();
        List<AppointmentView> appointments = receptionMapper
                .selectAppointments(doctorId, today).stream().map(this::toAppointmentView).toList();
        return new ReceptionDashboard(today.toString(), schedules, appointments);
    }

    public AppointmentDetail detail(long staffId, long appointmentId) {
        long doctorId = requireDoctor(staffId);
        Appointment appointment = receptionMapper.selectAppointment(appointmentId, doctorId);
        if (appointment == null) {
            throw new ApiException(404, "挂号单不存在");
        }
        ConsultationRecord record = consultationRecordMapper.selectByAppointmentId(appointmentId);
        return new AppointmentDetail(toAppointmentView(appointment),
                record == null ? null : record.getDiagnosis(),
                record == null ? null : record.getAdvice(),
                record == null || record.getCreatedAt() == null
                        ? null : record.getCreatedAt().toString());
    }

    public AppointmentDetail complete(long staffId, long appointmentId,
                                      String diagnosis, String advice) {
        long doctorId = requireDoctor(staffId);
        transactionTemplate.executeWithoutResult(status -> {
            // 挂号单行锁将诊断落库与状态流转绑定为一个事务，避免重复接诊生成两份记录。
            Appointment appointment = receptionMapper
                    .selectAppointmentForUpdate(appointmentId, doctorId);
            if (appointment == null) {
                throw new ApiException(404, "挂号单不存在");
            }
            if (Appointment.STATUS_VISITED.equals(appointment.getStatus())) {
                return;
            }
            if (!Appointment.STATUS_BOOKED.equals(appointment.getStatus())) {
                throw new ApiException(409, "当前状态不可接诊");
            }
            ConsultationRecord record = new ConsultationRecord();
            record.setAppointmentId(appointmentId);
            record.setDoctorId(doctorId);
            record.setDiagnosis(diagnosis.trim());
            record.setAdvice(advice.trim());
            consultationRecordMapper.insert(record);
            if (receptionMapper.markVisited(appointmentId) != 1) {
                throw new IllegalStateException("接诊状态流转失败");
            }
        });
        return detail(staffId, appointmentId);
    }

    private long requireDoctor(long staffId) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole())
                || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        return staff.getDoctorId();
    }

    private ScheduleView toScheduleView(Schedule schedule) {
        return new ScheduleView(schedule.getId(), schedule.getTimeSlot().getValue(),
                schedule.getTotalSlots(), schedule.getRemainingSlots(),
                Boolean.TRUE.equals(schedule.getIsActive()));
    }

    private AppointmentView toAppointmentView(Appointment appointment) {
        String status = switch (appointment.getStatus()) {
            case Appointment.STATUS_VISITED -> "已接诊";
            case Appointment.STATUS_CANCELLED -> "已取消";
            default -> "已约";
        };
        return new AppointmentView(appointment.getId(), appointment.getScheduleId(),
                appointment.getPatientNickname(), appointment.getSequenceNumber(), status,
                appointment.getScheduleDate() == null ? null : appointment.getScheduleDate().toString(),
                appointment.getTimeSlot() == null ? null : appointment.getTimeSlot().getValue(),
                summaryWithoutDisclaimer(appointment.getConditionSummary()), ChatService.DISCLAIMER);
    }

    private String summaryWithoutDisclaimer(String summary) {
        if (summary == null) {
            return null;
        }
        String content = summary.replace(ChatService.DISCLAIMER, "").trim();
        return content.endsWith("。") ? content.substring(0, content.length() - 1) : content;
    }

    public record ReceptionDashboard(String date, List<ScheduleView> schedules,
                                     List<AppointmentView> appointments) { }

    public record ScheduleView(Long id, String timeSlot, Integer totalSlots,
                               Integer remainingSlots, boolean active) { }

    public record AppointmentView(Long id, Long scheduleId, String patientNickname,
                                  Integer sequenceNumber, String status, String scheduleDate,
                                  String timeSlot, String conditionSummary,
                                  String summaryDisclaimer) { }

    public record AppointmentDetail(AppointmentView appointment, String diagnosis,
                                    String advice, String completedAt) { }
}
