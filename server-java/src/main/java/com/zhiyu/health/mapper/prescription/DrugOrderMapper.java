package com.zhiyu.health.mapper.prescription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.prescription.DrugOrder;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DrugOrderMapper extends BaseMapper<DrugOrder> {
    @Select("SELECT * FROM drug_orders WHERE patient_id = #{patientId} ORDER BY created_at DESC, id DESC")
    List<DrugOrder> selectForPatient(@Param("patientId") long patientId);

    @Select(
            """
            <script>
            SELECT o.*, p.nickname AS patient_nickname
            FROM drug_orders o
            JOIN patients p ON p.id = o.patient_id
            <where>
              <if test='status != null and status != ""'>AND o.status = #{status}</if>
              <if test='pickupMethod != null and pickupMethod != ""'>AND o.pickup_method = #{pickupMethod}</if>
            </where>
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<DrugOrder> selectForAdmin(
            @Param("status") String status,
            @Param("pickupMethod") String pickupMethod,
            @Param("offset") int offset,
            @Param("size") int size);

    @Select(
            """
            <script>
            SELECT COUNT(*) FROM drug_orders o
            <where>
              <if test='status != null and status != ""'>AND o.status = #{status}</if>
              <if test='pickupMethod != null and pickupMethod != ""'>AND o.pickup_method = #{pickupMethod}</if>
            </where>
            </script>
            """)
    long countForAdmin(@Param("status") String status, @Param("pickupMethod") String pickupMethod);

    // B 端明细：JOIN patients 取昵称（selectById 不带 JOIN，无法回填 patientNickname）
    @Select(
            """
            SELECT o.*, p.nickname AS patient_nickname
            FROM drug_orders o
            JOIN patients p ON p.id = o.patient_id
            WHERE o.id = #{id}
            """)
    DrugOrder selectDetailedForAdmin(@Param("id") long id);

    @Select(
            """
            SELECT * FROM drug_orders
            WHERE id = #{id} AND patient_id = #{patientId}
            FOR UPDATE
            """)
    DrugOrder selectForPatientForUpdate(@Param("id") long id, @Param("patientId") long patientId);

    @Select("SELECT * FROM drug_orders WHERE id = #{id} FOR UPDATE")
    DrugOrder selectForUpdate(@Param("id") long id);

    // 惰性过期收口（票 88）：捞出已过支付截止的待支付订单并加行锁，patientId 为空时全量（B 端入口）。
    // 行锁保证并发入口（list/detail/pay/cancel 同时命中）不会重复回补库存。
    @Select(
            """
            <script>
            SELECT * FROM drug_orders
            WHERE status = #{unpaidStatus} AND payment_deadline &lt; now()
            <if test='patientId != null'>AND patient_id = #{patientId}</if>
            ORDER BY id
            FOR UPDATE
            </script>
            """)
    List<DrugOrder> selectOverdueUnpaidForUpdate(
            @Param("patientId") Long patientId, @Param("unpaidStatus") String unpaidStatus);

    // 处方防重预检（票 88）：同处方已存在未取消/未过期订单即 409；并发穿透由
    // uq_drug_orders_active_prescription 部分唯一索引兜底（DuplicateKeyException → 409）。
    @Select(
            """
            SELECT COUNT(*) FROM drug_orders
            WHERE prescription_id = #{prescriptionId}
              AND status NOT IN (#{cancelledStatus}, #{expiredStatus})
            """)
    long countActiveByPrescription(
            @Param("prescriptionId") long prescriptionId,
            @Param("cancelledStatus") String cancelledStatus,
            @Param("expiredStatus") String expiredStatus);

    @Update(
            """
            UPDATE drug_orders SET status = #{paidStatus}, paid_at = now()
            WHERE id = #{id} AND status = #{unpaidStatus}
            """)
    int markPaid(
            @Param("id") long id, @Param("paidStatus") String paidStatus, @Param("unpaidStatus") String unpaidStatus);

    @Update(
            """
            UPDATE drug_orders SET status = #{cancelledStatus}, cancelled_at = now()
            WHERE id = #{id} AND status = #{unpaidStatus}
            """)
    int cancel(
            @Param("id") long id,
            @Param("cancelledStatus") String cancelledStatus,
            @Param("unpaidStatus") String unpaidStatus);

    @Update(
            """
            UPDATE drug_orders SET status = #{expiredStatus}, expired_at = now()
            WHERE id = #{id} AND status = #{unpaidStatus}
            """)
    int expire(
            @Param("id") long id,
            @Param("expiredStatus") String expiredStatus,
            @Param("unpaidStatus") String unpaidStatus);

    // 履约状态机（票 88，ADR-0035）：全部条件更新，0 行即并发/非法跳转，service 抛 409。
    // SHIP/READY 额外限定取药方式，配送单不得进入自取路径，反之亦然。
    @Update(
            """
            UPDATE drug_orders SET status = #{dispensing}, dispensing_at = now()
            WHERE id = #{id} AND status = #{paid}
            """)
    int markDispensing(@Param("id") long id, @Param("dispensing") String dispensing, @Param("paid") String paid);

    @Update(
            """
            UPDATE drug_orders SET status = #{shipped}, shipped_at = now(),
              carrier_name = #{carrierName}, tracking_no = #{trackingNo}
            WHERE id = #{id} AND status = #{dispensing} AND pickup_method = #{delivery}
            """)
    int markShipped(
            @Param("id") long id,
            @Param("shipped") String shipped,
            @Param("dispensing") String dispensing,
            @Param("delivery") String delivery,
            @Param("carrierName") String carrierName,
            @Param("trackingNo") String trackingNo);

    @Update(
            """
            UPDATE drug_orders SET status = #{delivered}, delivered_at = now()
            WHERE id = #{id} AND status = #{shipped}
            """)
    int markDelivered(@Param("id") long id, @Param("delivered") String delivered, @Param("shipped") String shipped);

    @Update(
            """
            UPDATE drug_orders SET status = #{ready}, ready_for_pickup_at = now()
            WHERE id = #{id} AND status = #{dispensing} AND pickup_method = #{pickup}
            """)
    int markReadyForPickup(
            @Param("id") long id,
            @Param("ready") String ready,
            @Param("dispensing") String dispensing,
            @Param("pickup") String pickup);

    @Update(
            """
            UPDATE drug_orders SET status = #{pickedUp}, picked_up_at = now()
            WHERE id = #{id} AND status = #{ready}
            """)
    int markPickedUp(@Param("id") long id, @Param("pickedUp") String pickedUp, @Param("ready") String ready);
}
