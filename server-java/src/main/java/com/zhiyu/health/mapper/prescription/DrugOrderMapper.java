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
            SELECT * FROM drug_orders
            <if test='status != null and status != ""'>WHERE status = #{status}</if>
            ORDER BY created_at DESC, id DESC
            </script>
            """)
    List<DrugOrder> selectForAdmin(@Param("status") String status);

    @Select(
            """
            SELECT * FROM drug_orders
            WHERE id = #{id} AND patient_id = #{patientId}
            FOR UPDATE
            """)
    DrugOrder selectForPatientForUpdate(@Param("id") long id, @Param("patientId") long patientId);

    @Select("SELECT * FROM drug_orders WHERE id = #{id} FOR UPDATE")
    DrugOrder selectForUpdate(@Param("id") long id);

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
            UPDATE drug_orders SET status = #{paidStatus}, paid_at = now()
            WHERE id = #{id} AND status = #{unpaidStatus}
            """)
    int markPaid(
            @Param("id") long id, @Param("paidStatus") String paidStatus, @Param("unpaidStatus") String unpaidStatus);

    @Update(
            """
            UPDATE drug_orders SET status = #{doneStatus}
            WHERE id = #{id} AND status = #{paidStatus}
            """)
    int complete(@Param("id") long id, @Param("doneStatus") String doneStatus, @Param("paidStatus") String paidStatus);
}
