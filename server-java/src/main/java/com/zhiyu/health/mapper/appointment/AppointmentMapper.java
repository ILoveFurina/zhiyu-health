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

    // 行锁 scope 到 patient+profile：同时完成患者归属校验与并发改单互斥，
    // cancel 路径据此保证重复取消只让首次状态转换进入双存储回补分支。
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

    // 就诊序号 MAX+1：本身非原子，依赖 reserve 临界区已持 schedule 行锁（selectByIdForUpdate）
    // 串行化取号，保证同一排班下并发挂号不重号；离开行锁调用会产生重号。
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

    // 病情摘要 CAS 写入：COALESCE(condition_summary, #{summary}) 只在原值为 NULL 时写入，
    // 已有摘要不覆盖（幂等重试返回的挂号单保留原会话摘要）；patient+profile+conversation 三重限定
    // 防止跨档案/跨会话误写。返回 0 由 service 报 404 挂号单不存在。
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

    // 挂号卡视图联查：LEFT JOIN payments（非 INNER）--挂号成功后收费单由 createUnpaid 异步补建，
    // 期间 payment_status 可能为 NULL；INNER JOIN 会让未建支付单的挂号卡从列表消失。
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

    // C 端挂号列表视图：LEFT JOIN payments 同上（payment_status 可能为 NULL）。
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
