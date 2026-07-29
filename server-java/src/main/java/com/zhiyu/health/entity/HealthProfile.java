package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 患者账号下的一份服务对象档案；会话仍归患者账号，不绑定本实体。 */
@Getter
@Setter
@TableName("health_profiles")
public class HealthProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private String displayName;
    private String gender;
    private LocalDate birthDate;
    private String relationship;
    private Boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
