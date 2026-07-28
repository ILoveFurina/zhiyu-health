package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** 患者会话；首条消息发出时惰性创建。 */
@TableName("conversations")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long patientId;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastActiveAt;

    public Conversation() {
    }

    public Conversation(Long id, Long patientId, String title) {
        this.id = id;
        this.patientId = patientId;
        this.title = title;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPatientId() { return patientId; }
    public void setPatientId(Long patientId) { this.patientId = patientId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(OffsetDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
}
