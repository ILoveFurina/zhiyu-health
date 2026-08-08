package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.AgentCallLog;
import com.zhiyu.health.mapper.AgentCallLogMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 调用日志（票 24）：工具进度事件落库与查询。
 *
 * 落库走独立可失败路径（ADR-0017）：调用方（ChatRoundService.forward）在独立 try-catch
 * 内调用 append，写入失败只 log.warn 不连坐主对话流。无任何原文列（硬约束 5）。
 * duration_ms 由本服务按 tool_start->tool_end 墙钟计算（server-py 不背时钟）。
 */
@Service
@RequiredArgsConstructor
public class AgentCallLogService {

    private final AgentCallLogMapper mapper;
    private final Contracts contracts;

    // 每轮 trace 状态：tool_call_id -> 开始墙钟纳秒，用于配对计算 duration_ms
    // 轮次结束由 clearRound 清理（ChatRoundService 在 finish 时调用）
    private final Map<Long, RoundTraceState> roundStates = new ConcurrentHashMap<>();

    /**
     * 追加一条工具进度事件。phase 由事件名决定（tool_start/tool_end）。
     * 调用方须在独立 try-catch 内调用，异常不向主对话流传播。
     */
    public void append(ChatRoundState roundState, String eventName, JsonNode data) {
        Contracts.SseEvents sse = contracts.sseEvents();
        if (!sse.isTraceEvent(eventName)) {
            return;
        }
        RoundTraceState state = roundStates.computeIfAbsent(roundState.roundId(), k -> new RoundTraceState());
        String toolCallId = text(data, "tool_call_id");
        String toolName = text(data, "tool_name");
        int seq = state.nextSeq();

        if (sse.toolStartEvent().equals(eventName)) {
            // 记录开始墙钟，配对 tool_end 时计算 duration_ms
            state.recordStart(toolCallId, System.nanoTime());
            mapper.insert(new AgentCallLog(
                    roundState.roundId(),
                    roundState.conversationId(),
                    roundState.patientId(),
                    toolCallId,
                    toolName,
                    AgentCallLog.PHASE_TOOL_START,
                    null,
                    null,
                    null,
                    seq));
            return;
        }

        // tool_end：配对 tool_start 计算墙钟耗时
        Long startNanos = state.takeStart(toolCallId);
        Integer durationMs = startNanos == null ? null : (int) ((System.nanoTime() - startNanos) / 1_000_000);
        String result = sse.isTraceResult(text(data, "result")) ? text(data, "result") : null;
        // error_code 只存契约白名单码；非白名单统一记 TOOL_ERROR_UNKNOWN（ADR-0017）
        String errorCode = "error".equals(result) ? contracts.sseEvents().traceErrorCodeUnknown() : null;
        // 脱敏响应摘要（server-py 已遮蔽敏感原文，硬约束 5）；缺失时落 null
        String summary = text(data, "tool_output_summary");
        mapper.insert(new AgentCallLog(
                roundState.roundId(),
                roundState.conversationId(),
                roundState.patientId(),
                toolCallId,
                toolName,
                AgentCallLog.PHASE_TOOL_END,
                result,
                durationMs,
                errorCode,
                summary,
                seq));
    }

    /** 清理轮次 trace 状态（ChatRoundService.finish 时调用，避免内存泄漏）。 */
    public void clearRound(Long roundId) {
        roundStates.remove(roundId);
    }

    /** B 端：有 trace 的会话摘要列表（按最近活跃倒序）。 */
    public List<ConversationView> listConversations() {
        return listConversations(null);
    }

    /** B 端：有 trace 的会话摘要列表，可按患者昵称模糊筛选（null/空则不筛）。 */
    public List<ConversationView> listConversations(String patientKeyword) {
        return mapper.selectConversationSummaries(patientKeyword).stream()
                .map(s -> new ConversationView(
                        s.conversationId(),
                        s.patientId(),
                        s.conversationTitle(),
                        s.patientNickname(),
                        s.lastActiveAt()))
                .toList();
    }

    /** B 端：指定会话的扁平事件列表（按 round_id + seq 还原顺序）。 */
    public List<AgentCallLog> listByConversation(Long conversationId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentCallLog>()
                .eq(AgentCallLog::getConversationId, conversationId)
                .orderByAsc(AgentCallLog::getRoundId)
                .orderByAsc(AgentCallLog::getSeq));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** 轮次身份上下文：由 ChatRoundService 在 forward 时传入。 */
    public record ChatRoundState(Long roundId, Long conversationId, Long patientId) {}

    /** 会话摘要（B 端列表视图）：会话 id + 标题 + 患者 id + 患者昵称 + 最近活跃时间。 */
    public record ConversationView(
            Long conversationId,
            Long patientId,
            String conversationTitle,
            String patientNickname,
            java.time.OffsetDateTime lastActiveAt) {}

    /** 每轮 trace 状态：seq 计数 + tool_call_id->开始纳秒配对。 */
    private static final class RoundTraceState {
        private final Map<String, Long> starts = new ConcurrentHashMap<>();
        private int seq = 0;

        synchronized int nextSeq() {
            return ++seq;
        }

        void recordStart(String toolCallId, long nanos) {
            if (toolCallId != null) {
                starts.put(toolCallId, nanos);
            }
        }

        Long takeStart(String toolCallId) {
            if (toolCallId == null) {
                return null;
            }
            return starts.remove(toolCallId);
        }
    }
}
