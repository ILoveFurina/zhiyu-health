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

    // 支付超时惰性收敛（票 81）：查过期待支付挂号单（轻量投影，不加锁），
    // 收敛入口据此逐条进入 withRefund 事务用 markCancelled 的 CAS 守卫并发；
    // 并发重复收敛对同一行 CAS 返回 0 即安全跳过，不依赖长事务行锁。
    @Select(
            """
            SELECT id, schedule_id FROM appointments
            WHERE status = #{pendingPaymentStatus}
              AND payment_deadline IS NOT NULL AND payment_deadline <= now()
            ORDER BY payment_deadline
            """)
    List<OverdueAppointment> selectOverduePending(@Param("pendingPaymentStatus") String pendingPaymentStatus);

    /** 过期待支付挂号单的轻量投影（id + 排班），供惰性收敛逐条退款。 */
    record OverdueAppointment(Long id, Long scheduleId) {}

    @Update(
            """
            UPDATE appointments SET status = #{cancelledStatus}, cancelled_at = now()
            WHERE id = #{appointmentId} AND status IN (#{pendingPaymentStatus}, #{bookedStatus})
            """)
    int markCancelled(
            @Param("appointmentId") long appointmentId,
            @Param("pendingPaymentStatus") String pendingPaymentStatus,
            @Param("bookedStatus") String bookedStatus,
            @Param("cancelledStatus") String cancelledStatus);

    // 支付完成推进挂号单 PENDING_PAYMENT -> BOOKED（票 81）；CAS 只接受待支付，
    // 并发支付由 payment 行锁先行拦截，此 UPDATE 作为挂号侧的二次幂等守卫。
    @Update(
            """
            UPDATE appointments SET status = #{bookedStatus}
            WHERE id = #{appointmentId} AND status = #{pendingPaymentStatus}
            """)
    int markBooked(
            @Param("appointmentId") long appointmentId,
            @Param("pendingPaymentStatus") String pendingPaymentStatus,
            @Param("bookedStatus") String bookedStatus);

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
