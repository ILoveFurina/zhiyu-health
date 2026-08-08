package com.zhiyu.health.entity.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 院区科室分类实体，镜像 schema.sql department_categories 表：每个院区独立维护一套分类 */
@Getter
@Setter
@TableName("department_categories")
public class DepartmentCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campusId;
    private String name;
    private Integer sortOrder;
}
