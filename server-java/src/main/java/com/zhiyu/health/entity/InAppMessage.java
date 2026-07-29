package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("in_app_messages")
public class InAppMessage {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private String type;
    private String title;
    private String content;
    private String disclaimer;
    private Long relatedAppointmentId;
    private OffsetDateTime createdAt;
}
