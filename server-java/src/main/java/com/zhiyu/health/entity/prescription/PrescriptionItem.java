package com.zhiyu.health.entity.prescription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prescription_items")
public class PrescriptionItem {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long prescriptionId;
    private Long medicationId;
    private String dosage;
    private String frequency;
    private String duration;
    // 票 88：医生开方时填写的正整数配药数量，患者不可修改。
    private Integer quantity;
    private String notes;

    @TableField(exist = false)
    private String medicationName;

    @TableField(exist = false)
    private String genericName;

    @TableField(exist = false)
    private String specification;
}
