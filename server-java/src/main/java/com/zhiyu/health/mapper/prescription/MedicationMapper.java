package com.zhiyu.health.mapper.prescription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.prescription.Medication;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MedicationMapper extends BaseMapper<Medication> {
    @Select("SELECT * FROM medications WHERE is_active = TRUE ORDER BY name")
    List<Medication> selectActive();

    @Select(
            """
            SELECT m.* FROM medications m
            JOIN prescription_items pi ON pi.medication_id = m.id
            WHERE pi.prescription_id = #{prescriptionId}
            ORDER BY m.id, pi.id
            FOR UPDATE OF m
            """)
    List<Medication> selectForPrescriptionForUpdate(@Param("prescriptionId") long prescriptionId);

    @Update(
            """
            UPDATE medications SET stock = stock - #{quantity}
            WHERE id = #{id} AND stock >= #{quantity}
            """)
    int deductStock(@Param("id") long id, @Param("quantity") int quantity);

    @Update("UPDATE medications SET stock = stock + #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") long id, @Param("quantity") int quantity);
}
