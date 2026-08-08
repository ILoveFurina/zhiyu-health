package com.zhiyu.health.entity.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 调用日志（票 24）：每条工具进度事件一行。
 *
 * 字段为白名单，无任何原文列（不存工具参数与返回体，硬约束 5）。
 * tool_start/tool_end 用 tool_call_id 配对；duration_ms 由 server-java 墙钟计算。
 */
@Getter
@Setter
@TableName("agent_call_logs")
public class AgentCallLog {

    public static final String PHASE_TOOL_START = "tool_start";
    public static final String PHASE_TOOL_END = "tool_end";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roundId;
    private Long conversationId;
    private Long patientId;
    private String toolCallId;
    private String toolName;
    private String phase;
    private String result;
    private Integer durationMs;
    private String errorCode;
    private String toolOutputSummary;
    private Integer seq;
    private OffsetDateTime createdAt;

    public AgentCallLog() {}

    public AgentCallLog(
            Long roundId,
            Long conversationId,
            Long patientId,
            String toolCallId,
            String toolName,
            String phase,
            String result,
            Integer durationMs,
            String errorCode,
            Integer seq) {
        this(roundId, conversationId, patientId, toolCallId, toolName, phase, result, durationMs, errorCode, null, seq);
    }

    public AgentCallLog(
            Long roundId,
            Long conversationId,
            Long patientId,
            String toolCallId,
            String toolName,
            String phase,
            String result,
            Integer durationMs,
            String errorCode,
            String toolOutputSummary,
            Integer seq) {
        this.roundId = roundId;
        this.conversationId = conversationId;
        this.patientId = patientId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.phase = phase;
        this.result = result;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.toolOutputSummary = toolOutputSummary;
        this.seq = seq;
    }
}
