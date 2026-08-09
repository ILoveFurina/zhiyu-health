package com.zhiyu.health.entity.health;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 健康观测（票 61，ADR-0031）：报告白名单项目经 server-java 确定性映射后的沉淀记录。
 * 追加式来源模型：纠错不覆盖旧记录，旧记录 SUPERSEDED/current=FALSE + 追加 USER_CORRECTION 新记录；
 * REJECTED 保持 current=TRUE 占用每日槽位，阻止重复上传复活。
 */
@Getter
@Setter
@TableName("health_observations")
public class HealthObservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long healthProfileId;
    private Long reportInterpretationId;
    private String metricCode;
    private BigDecimal valueNumeric;
    private String valueCategory;
    private String unit;
    private String referenceRange;
    private LocalDate observedOn;
    private String sourceType;
    private String verificationStatus;
    private Boolean current;
    private Long supersedesId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
