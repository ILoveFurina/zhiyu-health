package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Payment;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    @Insert(
            """
            INSERT INTO payments (appointment_id, amount, status)
            VALUES (#{payment.appointmentId}, #{payment.amount}, #{payment.status})
            ON CONFLICT (appointment_id) DO NOTHING
            """)
    int insertUnpaid(@Param("payment") Payment payment);

    @Select(
            """
            SELECT p.* FROM payments p
            JOIN appointments a ON a.id = p.appointment_id
            WHERE p.appointment_id = #{appointmentId} AND a.patient_id = #{patientId}
            FOR UPDATE OF p
            """)
    Payment selectForPatientForUpdate(@Param("appointmentId") long appointmentId, @Param("patientId") long patientId);

    @Select("SELECT * FROM payments WHERE id = #{id} FOR UPDATE")
    Payment selectForUpdate(@Param("id") long id);

    @Update(
            """
            UPDATE payments SET status = #{paidStatus}, paid_at = now()
            WHERE appointment_id = #{appointmentId} AND status = #{unpaidStatus}
            """)
    int markPaid(
            @Param("appointmentId") long appointmentId,
            @Param("paidStatus") String paidStatus,
            @Param("unpaidStatus") String unpaidStatus);

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
