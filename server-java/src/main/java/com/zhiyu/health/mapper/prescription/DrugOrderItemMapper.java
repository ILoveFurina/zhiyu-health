package com.zhiyu.health.mapper.prescription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.prescription.DrugOrderItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DrugOrderItemMapper extends BaseMapper<DrugOrderItem> {
    @Select(
            """
            SELECT doi.*, m.name AS medication_name, m.specification
            FROM drug_order_items doi
            JOIN medications m ON m.id = doi.medication_id
            WHERE doi.drug_order_id = #{orderId}
            ORDER BY doi.id
            """)
    List<DrugOrderItem> selectDetailed(@Param("orderId") long orderId);
}
