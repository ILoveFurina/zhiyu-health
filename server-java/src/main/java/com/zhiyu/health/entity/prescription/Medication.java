package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("medications")
public class Medication {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String genericName;
    private String specification;
    private String instructions;
    private BigDecimal price;
    private Integer stock;
    private Boolean isActive;
}
