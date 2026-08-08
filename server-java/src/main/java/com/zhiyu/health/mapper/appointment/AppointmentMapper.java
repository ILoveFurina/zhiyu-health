package com.zhiyu.health.mapper.appointment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.appointment.Appointment;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppointmentMapper extends BaseMapper<Appointment> {

    // 幂等判重只看有效挂号：已取消的记录允许重挂（与 uq_appointments_profile_schedule_active
    // 部分唯一索引同口径），DB 唯一索引兜底并发下的重复有效挂号。
    @Select(
            """
            SELECT * FROM appointments
            WHERE patient_id = #{patientId} AND health_profile_id = #{profileId} AND schedule_id = #{scheduleId}
              AND status <> #{cancelledStatus}
            """)
    Appointment selectForProfileAndSchedule(
            @Param("patientId") long patientId,
            @Param("profileId") long profileId,
            @Param("scheduleId") long scheduleId,
            @Param("cancelledStatus") String cancelledStatus);

    @Select(
            """
            SELECT * FROM appointments
            WHERE id = #{appointmentId} AND patient_id = #{patientId} AND health_profile_id = #{profileId}
            FOR UPDATE
            """)
    Appointment selectByIdForUpdate(
            @Param("appointmentId") long appointmentId,
            @Param("patientId") long patientId,
            @Param("profileId") long profileId);

    @Select("SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM appointments WHERE schedule_id = #{scheduleId}")
    int nextSequenceNumber(@Param("scheduleId") long scheduleId);

    @Update(
            """
            UPDATE appointments SET status = #{cancelledStatus}, cancelled_at = now()
            WHERE id = #{appointmentId} AND status = #{bookedStatus}
            """)
    int markCancelled(
            @Param("appointmentId") long appointmentId,
            @Param("bookedStatus") String bookedStatus,
            @Param("cancelledStatus") String cancelledStatus);

    @Update(
            """
            UPDATE appointments
            SET condition_summary = COALESCE(condition_summary, #{summary})
            WHERE id = #{appointmentId}
              AND patient_id = #{patientId}
              AND health_profile_id = #{profileId}
              AND conversation_id = #{conversationId}
            """)
    int updateConditionSummary(
            @Param("appointmentId") long appointmentId,
            @Param("patientId") long patientId,
            @Param("profileId") long profileId,
            @Param("conversationId") long conversationId,
            @Param("summary") String summary);

    @Select(
            """
            SELECT a.*, s.doctor_id, d.name AS doctor_name, dep.name AS department_name,
                   s.schedule_date, s.time_slot, p.status AS payment_status,
                   h.name AS hospital_name, c.name AS campus_name, c.address AS campus_address
            FROM appointments a
            JOIN schedules s ON s.id = a.schedule_id
            JOIN doctors d ON d.id = s.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            JOIN hospital_campuses c ON c.id = dep.campus_id
            JOIN hospitals h ON h.id = c.hospital_id
            LEFT JOIN payments p ON p.appointment_id = a.id
            WHERE a.id = #{appointmentId}
            """)
    Appointment selectViewById(@Param("appointmentId") long appointmentId);

    @Select(
            """
            SELECT a.*, s.doctor_id, d.name AS doctor_name, dep.name AS department_name,
                   s.schedule_date, s.time_slot, p.status AS payment_status,
                   h.name AS hospital_name, c.name AS campus_name, c.address AS campus_address
            FROM appointments a
            JOIN schedules s ON s.id = a.schedule_id
            JOIN doctors d ON d.id = s.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            JOIN hospital_campuses c ON c.id = dep.campus_id
            JOIN hospitals h ON h.id = c.hospital_id
            LEFT JOIN payments p ON p.appointment_id = a.id
            WHERE a.patient_id = #{patientId} AND a.health_profile_id = #{profileId}
            ORDER BY s.schedule_date DESC, a.id DESC
            """)
    List<Appointment> selectViewsByProfile(@Param("patientId") long patientId, @Param("profileId") long profileId);
}
