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
    private String notes;

    @TableField(exist = false)
    private String medicationName;

    @TableField(exist = false)
    private String genericName;

    @TableField(exist = false)
    private String specification;
}
