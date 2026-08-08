package com.zhiyu.health.entity.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 平台标准科室实体，镜像 schema.sql standard_departments 表（票 49「科类 → 标准科室」目录） */
@Getter
@Setter
@TableName("standard_departments")
public class StandardDepartment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String category;
    private String name;
    private Integer sortOrder;
}
