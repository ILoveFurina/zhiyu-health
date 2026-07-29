package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private Boolean isActive;
}
