package com.zhiyu.health.entity.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 院区实体，镜像 schema.sql hospital_campuses 表（票 49）：服务城市由本表 city_code/city_name 动态聚合 */
@Getter
@Setter
@TableName("hospital_campuses")
public class HospitalCampus {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hospitalId;
    private String name;
    private String cityCode;
    private String cityName;
    private String address;
    private Double longitude;
    private Double latitude;
    // 就诊指引卡静态来源（票 43 迁至院区）：演示用虚构静态 seed 值，非 LLM 生成
    private String floor;
    private String materials;
    private String precautions;
}
