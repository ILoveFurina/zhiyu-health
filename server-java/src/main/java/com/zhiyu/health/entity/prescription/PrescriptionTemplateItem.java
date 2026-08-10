package com.zhiyu.health.entity.prescription;

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
    // 票 88：医生填写的正整数配药数量，引用模板开方时带入处方明细。
    private Integer quantity;
    private String notes;

    @TableField(exist = false)
    private String medicationName;

    @TableField(exist = false)
    private String specification;
}
