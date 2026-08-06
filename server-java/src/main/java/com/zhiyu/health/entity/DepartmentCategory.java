package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 医院科室分类实体，镜像 schema.sql department_categories 表（票 49）：各院区共享该医院的分类体系 */
@Getter
@Setter
@TableName("department_categories")
public class DepartmentCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hospitalId;
    private String name;
    private Integer sortOrder;
}
