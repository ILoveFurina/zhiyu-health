package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Payment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    @Insert(
            """
            INSERT INTO payments (appointment_id, amount, status)
            VALUES (#{payment.appointmentId}, #{payment.amount}, #{payment.status})
            ON CONFLICT (appointment_id) DO NOTHING
            """)
    int insertUnpaid(@Param("payment") Payment payment);
}
