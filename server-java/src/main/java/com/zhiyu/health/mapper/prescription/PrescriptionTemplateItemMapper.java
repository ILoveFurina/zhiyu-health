package com.zhiyu.health.mapper.prescription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.prescription.PrescriptionTemplateItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PrescriptionTemplateItemMapper extends BaseMapper<PrescriptionTemplateItem> {
    @Select(
            """
            SELECT pti.*, m.name AS medication_name, m.specification
            FROM prescription_template_items pti JOIN medications m ON m.id = pti.medication_id
            WHERE pti.template_id = #{templateId} ORDER BY pti.id
            """)
    List<PrescriptionTemplateItem> selectDetailed(@Param("templateId") long templateId);
}
