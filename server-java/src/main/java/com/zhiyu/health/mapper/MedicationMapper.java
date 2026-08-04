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

    /**
     * 票 14（ADR-0025 差异化点 1）：按商品名（name）与通用名（generic_name）双列精确查。
     * vision 提取的候选药名两列都比对；name 有 UNIQUE 约束精确唯一，generic_name 可能多行。
     * is_active 过滤与 ContraindicationService.check 的校验一致，停用药品不返回。
     */
    @Select(
            """
            SELECT * FROM medications
            WHERE is_active = TRUE AND (name = #{name} OR generic_name = #{name})
            ORDER BY name
            """)
    List<Medication> selectActiveByNameOrGeneric(@Param("name") String name);

    /**
     * 票 14：模糊匹配兜底。vision OCR 提取不完全准确时，精确查无果再用 LIKE 双列模糊查。
     * keyword 由 caller 包裹 % 通配符（如 "%阿莫西林%"），ILIKE 大小写不敏感，LIMIT 20 防爆。
     */
    @Select(
            """
            SELECT * FROM medications
            WHERE is_active = TRUE
              AND (name ILIKE #{keyword} OR generic_name ILIKE #{keyword})
            ORDER BY name LIMIT 20
            """)
    List<Medication> selectActiveByNameLike(@Param("keyword") String keyword);

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
