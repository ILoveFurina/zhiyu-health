package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 在线问诊医患消息实体，镜像 schema.sql online_consultation_messages 表（票 54：独立持久化，不进 Agent 会话） */
@Getter
@Setter
@TableName("online_consultation_messages")
public class OnlineConsultationMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long consultationId;
    private String senderType;
    private String content;
    private OffsetDateTime createdAt;
}
