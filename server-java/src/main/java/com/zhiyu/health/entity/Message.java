package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 会话消息；red_flag 是规则引擎产物，不属于 AI 产出。 */
@Getter
@Setter
@TableName("messages")
public class Message {

    public static final String KIND_TEXT = "text";
    public static final String KIND_DOCTOR_RECOMMENDATIONS = "doctor_recommendations";
    public static final String KIND_DOCTOR_SLOTS = "doctor_slots";
    public static final String KIND_HOSPITAL_RECOMMENDATIONS = "hospital_recommendations";
    public static final String KIND_APPOINTMENT = "appointment";
    public static final String KIND_APPOINTMENTS = "appointments";
    public static final String KIND_REPORT_UPLOAD = "report_upload";
    public static final String KIND_REPORT_INTERPRETATION = "report_interpretation";
    public static final String KIND_REPORT_CONTEXT = "report_context";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String role;
    private String kind;
    private String content;
    private String effort;
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
        return KIND_DOCTOR_RECOMMENDATIONS.equals(kind)
                || KIND_DOCTOR_SLOTS.equals(kind)
                || KIND_HOSPITAL_RECOMMENDATIONS.equals(kind)
                || KIND_APPOINTMENT.equals(kind)
                || KIND_APPOINTMENTS.equals(kind)
                || KIND_REPORT_INTERPRETATION.equals(kind);
    }
}
