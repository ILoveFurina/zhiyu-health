package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** 会话消息；red_flag 是规则引擎产物，不属于 AI 产出。 */
@TableName("messages")
public class Message {

    public static final String KIND_TEXT = "text";
    public static final String KIND_DOCTOR_RECOMMENDATIONS = "doctor_recommendations";
    public static final String KIND_DOCTOR_SLOTS = "doctor_slots";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String role;
    private String kind;
    private String content;
    private String effort;
    private OffsetDateTime createdAt;

    public Message() {
    }

    public Message(Long id, Long conversationId, String role, String kind, String content, String effort) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.kind = kind;
        this.content = content;
        this.effort = effort;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public static boolean isAiCardKind(String kind) {
        return KIND_DOCTOR_RECOMMENDATIONS.equals(kind) || KIND_DOCTOR_SLOTS.equals(kind);
    }
}
