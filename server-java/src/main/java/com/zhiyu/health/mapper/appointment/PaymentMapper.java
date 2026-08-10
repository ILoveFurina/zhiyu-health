package com.zhiyu.health.mapper.appointment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.appointment.Payment;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    // 幂等建单守卫：ON CONFLICT (appointment_id) DO NOTHING 依赖 appointment_id 唯一约束（一挂号一支付单），
    // 并发补建收费记录时只让首条成功；返回值可能为 0 被上层忽略--挂号结果不依赖收费单是否新建成功。
    @Insert(
            """
            INSERT INTO payments (appointment_id, amount, status)
            VALUES (#{payment.appointmentId}, #{payment.amount}, #{payment.status})
            ON CONFLICT (appointment_id) DO NOTHING
            """)
    int insertUnpaid(@Param("payment") Payment payment);

    // 锁定收费行后再判断状态，使 C/B 两个支付入口不能同时把同一笔收费重复流转。
    @Select(
            """
            SELECT p.* FROM payments p
            JOIN appointments a ON a.id = p.appointment_id
            WHERE p.appointment_id = #{appointmentId} AND a.patient_id = #{patientId}
            FOR UPDATE OF p
            """)
    Payment selectForPatientForUpdate(@Param("appointmentId") long appointmentId, @Param("patientId") long patientId);

    // B 端管理员按 paymentId 持锁：无 patient 归属校验（管理员可代收任意患者挂号费），
    // 患者身份校验由 payForPatient 路径的 selectForPatientForUpdate 承担。
    @Select("SELECT * FROM payments WHERE id = #{id} FOR UPDATE")
    Payment selectForUpdate(@Param("id") long id);

    // 旧状态谓词是锁之外的最终原子护栏；受影响行为 0 时由 service 报并发冲突，paid_at 不会误写。
    @Update(
            """
            UPDATE payments p
            SET status = #{paidStatus}, paid_at = now()
            FROM appointments a
            WHERE a.id = p.appointment_id
              AND p.appointment_id = #{appointmentId}
              AND p.status = #{unpaidStatus}
              AND a.status = #{pendingPaymentStatus}
            """)
    int markPaid(
            @Param("appointmentId") long appointmentId,
            @Param("paidStatus") String paidStatus,
            @Param("unpaidStatus") String unpaidStatus,
            @Param("pendingPaymentStatus") String pendingPaymentStatus);

    // 退款 CAS（票 90）：只接受 paid->refunded，refunded_at 只写一次。
    // 与取消挂号同事务：markCancelled 推进挂号单 CANCELLED 后由 refundIfPaid 调用本方法。
    // 返回 1 表示首次退款成功；返回 0 表示已退款（并发重复取消）或非 paid（未支付取消），均安全跳过。
    @Update(
            """
            UPDATE payments SET status = #{refundedStatus}, refunded_at = now()
            WHERE appointment_id = #{appointmentId} AND status = #{paidStatus}
            """)
    int markRefunded(
            @Param("appointmentId") long appointmentId,
            @Param("refundedStatus") String refundedStatus,
            @Param("paidStatus") String paidStatus);

    @Select(
            """
            <script>
            SELECT * FROM payments
            <if test='status != null and status != ""'>WHERE status = #{status}</if>
            ORDER BY created_at DESC, id DESC
            </script>
            """)
    List<Payment> selectForAdmin(@Param("status") String status);
}
