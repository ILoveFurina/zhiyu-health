package com.zhiyu.health.mapper.prescription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.prescription.DrugOrderFulfillmentEvent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DrugOrderFulfillmentEventMapper extends BaseMapper<DrugOrderFulfillmentEvent> {
    @Select("SELECT * FROM drug_order_fulfillment_events WHERE drug_order_id = #{orderId} ORDER BY id")
    List<DrugOrderFulfillmentEvent> selectByOrderId(@Param("orderId") long orderId);
}
