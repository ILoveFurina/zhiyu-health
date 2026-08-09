package com.zhiyu.health.mapper.consultation;

import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.scheduling.Schedule;
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
              AND a.status IN (#{bookedStatus}, #{inProgressStatus}, #{visitedStatus})
            ORDER BY CASE s.time_slot WHEN '上午' THEN 1 WHEN '下午' THEN 2 ELSE 3 END,
                     a.sequence_number
            """)
    List<Appointment> selectAppointments(
            @Param("doctorId") long doctorId,
            @Param("date") LocalDate date,
            @Param("bookedStatus") String bookedStatus,
            @Param("inProgressStatus") String inProgressStatus,
            @Param("visitedStatus") String visitedStatus);

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
            WHERE id = #{appointmentId} AND status = #{inProgressStatus}
            """)
    // 票 87：接诊完成只能从就诊中推进（废弃 BOOKED -> VISITED 直通兜底），
    // SQL 只接受单一来源态，与契约 complete.from = [IN_PROGRESS] 保持一致。
    int markVisited(
            @Param("appointmentId") long appointmentId,
            @Param("inProgressStatus") String inProgressStatus,
            @Param("visitedStatus") String visitedStatus);

    // 单叫号约束（票 81，ADR-0033）：医生维度（跨当天所有排班）同时只能一条就诊中。
    // 叫号事务内行锁该医生的就诊中行：存在则阻塞并返回非空，调用方据此 409 拒绝。
    // FOR UPDATE 锁住既有 IN_PROGRESS 行串行化并发叫号，防止两个 B 端 tab 同时叫号的 TOCTOU。
    @Select(
            """
            SELECT a.id FROM appointments a
            JOIN schedules s ON s.id = a.schedule_id
            WHERE s.doctor_id = #{doctorId} AND a.status = #{inProgressStatus}
            LIMIT 1
            FOR UPDATE
            """)
    Long selectInProgressForDoctor(
            @Param("doctorId") long doctorId, @Param("inProgressStatus") String inProgressStatus);
}
