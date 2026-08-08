package com.zhiyu.health.mapper;

import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.Schedule;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReceptionMapper {

    @Select(
            """
            SELECT * FROM schedules
            WHERE doctor_id = #{doctorId} AND schedule_date = #{date}
            ORDER BY CASE time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END
            """)
    List<Schedule> selectSchedules(@Param("doctorId") long doctorId, @Param("date") LocalDate date);

    @Select(
            """
            SELECT a.*, s.doctor_id, s.schedule_date, s.time_slot,
                   hp.display_name AS patient_nickname
            FROM appointments a
            JOIN schedules s ON s.id = a.schedule_id
            JOIN health_profiles hp ON hp.id = a.health_profile_id
            WHERE s.doctor_id = #{doctorId} AND s.schedule_date = #{date}
              AND a.status <> #{cancelledStatus}
            ORDER BY CASE s.time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END,
                     a.sequence_number
            """)
    List<Appointment> selectAppointments(
            @Param("doctorId") long doctorId,
            @Param("date") LocalDate date,
            @Param("cancelledStatus") String cancelledStatus);

    @Select(
            """
            SELECT a.*, s.doctor_id, s.schedule_date, s.time_slot,
                   hp.display_name AS patient_nickname
            FROM appointments a
            JOIN schedules s ON s.id = a.schedule_id
            JOIN health_profiles hp ON hp.id = a.health_profile_id
            WHERE a.id = #{appointmentId} AND s.doctor_id = #{doctorId}
            """)
    Appointment selectAppointment(@Param("appointmentId") long appointmentId, @Param("doctorId") long doctorId);

    @Select(
            """
            SELECT a.*, s.schedule_date, s.time_slot, dep.location AS room
            FROM appointments a
            JOIN schedules s ON s.id = a.schedule_id
            JOIN doctors d ON d.id = s.doctor_id
            JOIN departments dep ON dep.id = d.department_id
            WHERE a.id = #{appointmentId} AND s.doctor_id = #{doctorId}
            FOR UPDATE OF a
            """)
    Appointment selectAppointmentForUpdate(
            @Param("appointmentId") long appointmentId, @Param("doctorId") long doctorId);

    @Update(
            """
            UPDATE appointments SET status = #{inProgressStatus}
            WHERE id = #{appointmentId} AND status = #{bookedStatus}
            """)
    int markInProgress(
            @Param("appointmentId") long appointmentId,
            @Param("bookedStatus") String bookedStatus,
            @Param("inProgressStatus") String inProgressStatus);

    @Update(
            """
            UPDATE appointments SET status = #{visitedStatus}
            WHERE id = #{appointmentId} AND status IN (#{bookedStatus}, #{inProgressStatus})
            """)
    int markVisited(
            @Param("appointmentId") long appointmentId,
            @Param("bookedStatus") String bookedStatus,
            @Param("inProgressStatus") String inProgressStatus,
            @Param("visitedStatus") String visitedStatus);
}
