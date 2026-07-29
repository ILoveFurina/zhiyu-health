package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 科室实体，镜像 schema.sql departments 表（floor/location 是就诊指引卡数据源） */
@Getter
@Setter
@TableName("departments")
public class Department {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hospitalId;
    private String name;
    private String floor;
    private String location;
}
