package com.zhiyu.health.entity.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 科室实体，镜像 schema.sql departments 表（票 49：归属院区 + 医院分类 + 非空标准科室映射） */
@Getter
@Setter
@TableName("departments")
public class Department {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campusId;
    private Long categoryId;
    private Long standardDepartmentId;
    private String name;
    private String floor;
    private String location;
}
