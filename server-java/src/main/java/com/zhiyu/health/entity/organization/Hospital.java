package com.zhiyu.health.entity.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 医院实体，镜像 schema.sql hospitals 表（票 49：地址/坐标/就诊指引静态值已下沉到院区） */
@Getter
@Setter
@TableName("hospitals")
public class Hospital {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String level;
}
