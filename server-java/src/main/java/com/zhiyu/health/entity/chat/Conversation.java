package com.zhiyu.health.entity.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 患者会话；首条消息发出时惰性创建。 */
@Getter
@Setter
@TableName("conversations")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastActiveAt;

    public Conversation() {}

    public Conversation(Long id, Long patientId, String title) {
        this.id = id;
        this.patientId = patientId;
        this.title = title;
    }
}
