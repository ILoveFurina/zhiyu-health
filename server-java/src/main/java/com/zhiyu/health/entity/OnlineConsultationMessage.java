package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiyu.health.config.Contracts;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 在线问诊医患消息实体，镜像 schema.sql online_consultation_messages 表
 * （票 55：独立持久化，不进 Agent 会话；票 58：kind 区分 text/image，image 的 content 存
 * {"object_key","media_type"} JSON，与 messages 表 image kind 同构）。
 * kind 字符串唯一事实源是 contracts/online-consultation.json message_kinds；
 * 实体无法注入 Spring Bean，故静态加载同一契约赋值（兼容壳），与 Message 实体同一套路。
 */
@Getter
@Setter
@TableName("online_consultation_messages")
public class OnlineConsultationMessage {

    private static final Contracts.OnlineConsultation ONLINE_CONSULTATION =
            Contracts.load(Contracts.resolveDir()).onlineConsultation();

    // 与契约 messageKinds 的顺序一一对应
    public static final String KIND_TEXT = ONLINE_CONSULTATION.messageKinds().get(0);
    public static final String KIND_IMAGE = ONLINE_CONSULTATION.messageKinds().get(1);

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long consultationId;
    private String senderType;
    private String kind;
    private String content;
    private OffsetDateTime createdAt;
}
