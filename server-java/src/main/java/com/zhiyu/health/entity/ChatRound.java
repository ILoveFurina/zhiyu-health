package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 一次用户请求及其处理结果的持久化生命周期；实时连接只观察它。 */
@Getter
@Setter
@TableName("chat_rounds")
public class ChatRound {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private String requestId;
    private Long conversationId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String status;
    private String errorCode;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    public ChatRound() {}

    public ChatRound(Long patientId, String requestId, Long conversationId, Long userMessageId, String status) {
        this.patientId = patientId;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.userMessageId = userMessageId;
        this.status = status;
    }
}
