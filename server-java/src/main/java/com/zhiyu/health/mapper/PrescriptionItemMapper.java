package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.PrescriptionItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PrescriptionItemMapper extends BaseMapper<PrescriptionItem> {
    @Select(
            """
            SELECT pi.*, m.name AS medication_name, m.generic_name, m.specification
            FROM prescription_items pi JOIN medications m ON m.id = pi.medication_id
            WHERE pi.prescription_id = #{prescriptionId} ORDER BY pi.id
            """)
    List<PrescriptionItem> selectDetailed(@Param("prescriptionId") long prescriptionId);
}
