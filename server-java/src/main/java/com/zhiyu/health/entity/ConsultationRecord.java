package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("consultation_records")
public class ConsultationRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appointmentId;
    private Long onlineConsultationId;
    private Long doctorId;
    private String diagnosis;
    private String advice;
    private OffsetDateTime createdAt;
}
