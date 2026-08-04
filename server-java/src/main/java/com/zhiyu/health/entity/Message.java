package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhiyu.health.config.Contracts;
import java.time.OffsetDateTime;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话消息；red_flag 是规则引擎产物，不属于 AI 产出。
 * kind 字符串唯一事实源是 contracts/sse-events.json；实体无法注入 Spring Bean，
 * 故静态加载同一契约赋值（兼容壳），取值与契约的一致由 ContractsConsistencyTest 钉死。
 */
@Getter
@Setter
@TableName("messages")
public class Message {

    private static final Contracts.SseEvents SSE_EVENTS =
            Contracts.load(Contracts.resolveDir()).sseEvents();

    // 与契约 messageKinds 的顺序一一对应
    public static final String KIND_TEXT = SSE_EVENTS.messageKinds().get(0);
    public static final String KIND_DOCTOR_RECOMMENDATIONS =
            SSE_EVENTS.messageKinds().get(1);
    public static final String KIND_DOCTOR_SLOTS = SSE_EVENTS.messageKinds().get(2);
    public static final String KIND_HOSPITAL_RECOMMENDATIONS =
            SSE_EVENTS.messageKinds().get(3);
    public static final String KIND_APPOINTMENT = SSE_EVENTS.messageKinds().get(4);
    public static final String KIND_APPOINTMENTS = SSE_EVENTS.messageKinds().get(5);
    public static final String KIND_REPORT_UPLOAD = SSE_EVENTS.messageKinds().get(6);
    public static final String KIND_REPORT_INTERPRETATION =
            SSE_EVENTS.messageKinds().get(7);
    public static final String KIND_REPORT_CONTEXT = SSE_EVENTS.messageKinds().get(8);
    private static final Set<String> AI_CARD_KINDS = Set.copyOf(SSE_EVENTS.aiCardKinds());

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String role;
    private String kind;
    private String content;
    private String effort;
    // 票 44：C 端 Agent 回复的情绪标注（calm/anxious/fearful），由 server-py 串行二次
    // LLM 调用产生挂 message 事件，server-java 透传落库供历史回看复现情绪色；用户消息为 NULL。
    private String emotion;
    private Long reportInterpretationId;
    private OffsetDateTime createdAt;

    public Message() {}

    public Message(Long id, Long conversationId, String role, String kind, String content, String effort) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.kind = kind;
        this.content = content;
        this.effort = effort;
    }

    public static boolean isAiCardKind(String kind) {
        return AI_CARD_KINDS.contains(kind);
    }
}
