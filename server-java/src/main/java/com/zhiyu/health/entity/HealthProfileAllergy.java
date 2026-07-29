package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("health_profile_allergies")
public class HealthProfileAllergy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long healthProfileId;
    private String allergen;
}
