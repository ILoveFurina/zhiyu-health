package com.zhiyu.health.mapper.prescription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.prescription.Medication;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MedicationMapper extends BaseMapper<Medication> {

    // 票 77/88：AI 购药点名查药，只查 OTC（is_prescription=FALSE）标准目录药品，
    // 按 name ILIKE 模糊匹配；价格/库存语义在 pharmacy_medications，本查询不带。
    // name 为空时退化为返回全部 OTC 药品（编排/工具层兜底空串），上限由调用方 Java 侧截断。
    @Select(
            """
            <script>
            SELECT * FROM medications WHERE is_prescription = FALSE
            <if test='name != null and name != ""'>
                AND name ILIKE CONCAT('%', #{name}, '%')
            </if>
            ORDER BY name
            </script>
            """)
    List<Medication> searchOtc(@Param("name") String name);
}
