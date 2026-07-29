package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 医生实体，镜像 schema.sql doctors 表 */
@Getter
@Setter
@TableName("doctors")
public class Doctor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long departmentId;
    private String name;
    private String title;
    private String specialty;
    private String photoUrl;
}
