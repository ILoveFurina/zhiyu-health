package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Medication;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MedicationMapper extends BaseMapper<Medication> {
    @Select("SELECT * FROM medications WHERE is_active = TRUE ORDER BY name")
    List<Medication> selectActive();

    // 票 75：AI 购药点名查药，只查 OTC（is_prescription=FALSE）且在售药品，按 name ILIKE 模糊匹配。
    // name 为空时退化为返回全部 OTC 药品（编排/工具层兜底空串），上限由调用方 Java 侧截断。
    @Select(
            """
            <script>
            SELECT * FROM medications WHERE is_active = TRUE AND is_prescription = FALSE
            <if test='name != null and name != ""'>
                AND name ILIKE CONCAT('%', #{name}, '%')
            </if>
            ORDER BY name
            </script>
            """)
    List<Medication> searchActiveOtc(@Param("name") String name);

    @Select(
            """
            SELECT m.* FROM medications m
            JOIN prescription_items pi ON pi.medication_id = m.id
            WHERE pi.prescription_id = #{prescriptionId}
            ORDER BY m.id, pi.id
            FOR UPDATE OF m
            """)
    List<Medication> selectForPrescriptionForUpdate(@Param("prescriptionId") long prescriptionId);

    // 票 74：OTC 直接下单按 medication_id 列表加行锁查药，与处方路径同构固定锁顺序。
    @Select(
            """
            <script>
            SELECT * FROM medications WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY id
            FOR UPDATE
            </script>
            """)
    List<Medication> selectByIdsForUpdate(@Param("ids") List<Long> ids);

    @Update(
            """
            UPDATE medications SET stock = stock - #{quantity}
            WHERE id = #{id} AND stock >= #{quantity}
            """)
    int deductStock(@Param("id") long id, @Param("quantity") int quantity);

    @Update("UPDATE medications SET stock = stock + #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") long id, @Param("quantity") int quantity);
}
