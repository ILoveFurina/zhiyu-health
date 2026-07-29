package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 医院实体，镜像 schema.sql hospitals 表 */
@Getter
@Setter
@TableName("hospitals")
public class Hospital {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String level;
    private String address;
    private Double longitude;
    private Double latitude;
}
