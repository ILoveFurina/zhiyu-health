package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("payments")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appointmentId;
    private BigDecimal amount;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime paidAt;
}
