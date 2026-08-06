package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prescription_templates")
public class PrescriptionTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Long doctorId;
    private OffsetDateTime createdAt;
}
