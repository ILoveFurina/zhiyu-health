package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prescription_template_items")
public class PrescriptionTemplateItem {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;
    private Long medicationId;
    private String dosage;
    private String frequency;
    private String duration;
    private String notes;

    @TableField(exist = false)
    private String medicationName;

    @TableField(exist = false)
    private String specification;
}
