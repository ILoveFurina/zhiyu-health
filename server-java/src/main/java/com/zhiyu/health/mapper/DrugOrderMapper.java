package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.DrugOrder;
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
            SELECT * FROM drug_orders
            WHERE id = #{id} AND patient_id = #{patientId}
            FOR UPDATE
            """)
    DrugOrder selectForPatientForUpdate(@Param("id") long id, @Param("patientId") long patientId);

    @Update(
            """
            UPDATE drug_orders SET status = #{cancelledStatus}, cancelled_at = now()
            WHERE id = #{id} AND status = #{unpaidStatus}
            """)
    int cancel(
            @Param("id") long id,
            @Param("cancelledStatus") String cancelledStatus,
            @Param("unpaidStatus") String unpaidStatus);
}
