package com.zhiyu.health.entity.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 医生实体，镜像 schema.sql doctors 表 */
@Getter
@Setter
@TableName("doctors")
public class Doctor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long departmentId;
    private String name;
    private String gender;
    private LocalDate birthDate;
    private String title;
    private BigDecimal registrationFee;
    private String specialty;
    private String photoUrl;
}
