package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Appointment;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppointmentMapper extends BaseMapper<Appointment> {

    @Select(
            """
            SELECT * FROM appointments
            WHERE patient_id = #{patientId} AND health_profile_id = #{profileId} AND schedule_id = #{scheduleId}
            """)
    Appointment selectForProfileAndSchedule(
            @Param("patientId") long patientId,
            @Param("profileId") long profileId,
            @Param("scheduleId") long scheduleId);

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
            UPDATE appointments SET status = 'CANCELLED', cancelled_at = now()
            WHERE id = #{appointmentId} AND status = 'BOOKED'
            """)
    int markCancelled(@Param("appointmentId") long appointmentId);

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
                   s.schedule_date, s.time_slot
            FROM appointments a
            JOIN schedules s ON s.id = a.schedule_id
            JOIN doctors d ON d.id = s.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            WHERE a.id = #{appointmentId}
            """)
    Appointment selectViewById(@Param("appointmentId") long appointmentId);

    @Select(
            """
            SELECT a.*, s.doctor_id, d.name AS doctor_name, dep.name AS department_name,
                   s.schedule_date, s.time_slot
            FROM appointments a
            JOIN schedules s ON s.id = a.schedule_id
            JOIN doctors d ON d.id = s.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            WHERE a.patient_id = #{patientId} AND a.health_profile_id = #{profileId}
            ORDER BY s.schedule_date DESC, a.id DESC
            """)
    List<Appointment> selectViewsByProfile(@Param("patientId") long patientId, @Param("profileId") long profileId);
}
