package com.zhiyu.health.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.rule.RedFlagHit;
import com.zhiyu.health.rule.RedFlagRuleEngine;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/** 对话主干：持久化 → 红线前置 → Agent SSE → 出口兜底。 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    // 多轮工具回调的长对话整体耗时可超 60s（LLM 思考窗口无 SSE 字节），60s 会误杀正常流；
    // 下游断连经 emitter 回调取消上游订阅兜底，该超时只是回收死连接的最后保险（票 33）
    private static final long EMITTER_TIMEOUT_MS = 300_000L;
    private static final String RED_FLAG_TEMPLATE = "检测到紧急危险信号：%s。%s。导诊已中断。";

    private final AgentClient agentClient;
    private final ConversationService conversations;
    private final RedFlagRuleEngine redFlagRules;
    private final ObjectMapper objectMapper;
    private final DisclaimerService disclaimers;
    // SSE 事件名与 effort/scenario 默认值唯一事实源是 contracts/*.json
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;

    public SseEmitter chat(
            Long patientId,
            Long conversationId,
            String content,
            String effort,
            String scenario,
            Double longitude,
            Double latitude) {
        // 安全门先于会话与消息写入，更先于 Agent 调用。
        RedFlagHit hit = redFlagRules.judge(content);
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, content);
        conversations.appendMessage(conversation.getId(), "user", content, "text", null);

        if (hit != null) {
            return redFlagStream(conversation, hit);
        }
        return agentStream(conversation, effort, scenario, longitude, latitude);
    }

    private SseEmitter redFlagStream(Conversation conversation, RedFlagHit hit) {
        String warning = RED_FLAG_TEMPLATE.formatted(hit.ruleName(), hit.advice());
        // red_flag 是规则引擎产物（不属于契约 message_kinds 的 AI 产出），kind 保留本地字面量
        Message saved = conversations.appendMessage(conversation.getId(), "assistant", warning, "red_flag", null);
        Contracts.SseEvents sse = contracts.sseEvents();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        try {
            send(
                    emitter,
                    sse.metaEvent(),
                    objectMapper.createObjectNode().put("conversation_id", conversation.getId()));
            ObjectNode data = objectMapper
                    .createObjectNode()
                    .put("message_id", saved.getId())
                    .put("rule", hit.ruleName())
                    .put("content", warning)
                    .put("advice", hit.advice());
            send(emitter, sse.redFlagEvent(), data);
            send(emitter, sse.doneEvent(), objectMapper.createObjectNode());
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private SseEmitter agentStream(
            Conversation conversation, String effort, String scenario, Double longitude, Double latitude) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Map<String, Object> body = new HashMap<>();
        body.put("messages", conversations.recentContext(conversation.getId()));
        body.put("patient_id", conversation.getPatientId());
        body.put("conversation_id", conversation.getId());
        body.put("effort", blankToDefault(effort, contracts.chatDefaults().effortDefault()));
        body.put("scenario", blankToDefault(scenario, contracts.chatDefaults().scenarioDefault()));
        HealthProfileService.AgentProfileContext profile = healthProfiles.agentContext(conversation.getPatientId());
        if (profile != null) {
            body.put("health_profile", profile);
        }
        // 经纬度来自用户授权定位；拒绝授权时不传，server-py 的 find_hospitals 据此降级。
        if (longitude != null && latitude != null) {
            body.put("longitude", longitude);
            body.put("latitude", latitude);
        }

        // SSE 链路跨双栈多跳（端 → 本端中继 → server-py → LLM/工具回调），票 33 排查证明
        // 每一跳都必须能回答"流走到哪、在哪断"；只记录身份、档位与计数，不记患者原文（硬规则 5）。
        RelayState relay = new RelayState(emitter, conversation.getId());
        log.info(
                "chat relay start conversationId={} patientId={} effort={} scenario={}",
                conversation.getId(),
                conversation.getPatientId(),
                body.get("effort"),
                body.get("scenario"));
        Disposable subscription = agentClient
                .chat(body)
                .subscribe(
                        event -> forwardAgentEvent(relay, event),
                        error -> terminateRelay(relay, RelayEnd.UPSTREAM_FAILED, error),
                        () -> terminateRelay(relay, RelayEnd.COMPLETE, null));
        // 端侧断连/超时/完成时取消上游订阅：uvicorn 才能收到 disconnect 并终止 Agent 运行，
        // 避免端侧已走而 LLM/工具继续空转（设计文档 §8：断连需取消下游请求并结束资源）。
        emitter.onCompletion(subscription::dispose);
        emitter.onError(error -> subscription.dispose());
        emitter.onTimeout(() -> {
            terminateRelay(relay, RelayEnd.TIMEOUT, null);
            subscription.dispose();
        });
        return emitter;
    }

    private void forwardAgentEvent(RelayState relay, ServerSentEvent<String> event) {
        Long conversationId = relay.conversationId;
        try {
            Contracts.SseEvents sse = contracts.sseEvents();
            String eventName = event.event();
            JsonNode data = parseData(event.data());
            if (sse.metaEvent().equals(eventName) && data instanceof ObjectNode object) {
                object.put("conversation_id", conversationId);
            } else if (sse.messageEvent().equals(eventName) && data instanceof ObjectNode object) {
                disclaimers.mount(object);
                Message saved = conversations.appendMessage(
                        conversationId,
                        "assistant",
                        object.path("content").asText(),
                        Message.KIND_TEXT,
                        nullableText(object.get("effort")));
                object.put("message_id", saved.getId());
            } else if (Message.isAiCardKind(eventName) && data instanceof ObjectNode object) {
                disclaimers.mount(object);
                Message saved = conversations.appendMessage(
                        conversationId, "assistant", objectMapper.writeValueAsString(object), eventName, null);
                object.put("message_id", saved.getId());
            }
            send(relay.emitter, eventName, data);
            relay.forwarded.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug(
                        "chat relay event conversationId={} name={} bytes={}",
                        conversationId,
                        eventName,
                        data.toString().length());
            }
        } catch (JsonProcessingException e) {
            // 本端序列化/解析失败属于 FORWARD_FAILED，不能误记为端侧断连（误导排查）
            terminateRelay(relay, RelayEnd.FORWARD_FAILED, e);
        } catch (IOException | IllegalStateException e) {
            // 下游写入失败：端侧连接已断，终止中继；上游订阅由 emitter 完成回调取消
            terminateRelay(relay, RelayEnd.DOWNSTREAM_BROKEN, e);
        } catch (RuntimeException e) {
            // 本端转换/持久化失败：票 33 的 kind 溢出曾把异常抛进 reactor，使上游被静默取消、
            // 日志只剩二次故障噪音；统一在此留痕并干净收尾
            terminateRelay(relay, RelayEnd.FORWARD_FAILED, e);
        }
    }

    /** 中继终结的唯一出口（幂等）：所有路径在此留痕，事件计数与耗时是断流定位的最小信息集。 */
    private void terminateRelay(RelayState relay, RelayEnd end, Throwable error) {
        if (!relay.terminated.compareAndSet(false, true)) {
            return;
        }
        long costMs = relay.costMs();
        switch (end) {
            case COMPLETE -> log.info(
                    "chat relay {} conversationId={} events={} costMs={}",
                    end.label,
                    relay.conversationId,
                    relay.forwarded.get(),
                    costMs);
            case TIMEOUT -> log.warn(
                    "chat relay {} conversationId={} events={} costMs={}",
                    end.label,
                    relay.conversationId,
                    relay.forwarded.get(),
                    costMs);
            case UPSTREAM_FAILED, DOWNSTREAM_BROKEN -> log.warn(
                    "chat relay {} conversationId={} events={} costMs={} error={}: {}",
                    end.label,
                    relay.conversationId,
                    relay.forwarded.get(),
                    costMs,
                    error == null ? "?" : error.getClass().getSimpleName(),
                    error == null ? "" : error.getMessage());
            case FORWARD_FAILED -> log.error(
                    "chat relay {} conversationId={} events={} costMs={}",
                    end.label,
                    relay.conversationId,
                    relay.forwarded.get(),
                    costMs,
                    error);
        }
        if (error != null && relay.forwarded.get() == 0) {
            // 响应尚未提交：走统一异常出口，端侧拿到干净的 HTTP 错误而非半截 SSE
            relay.emitter.completeWithError(error);
        } else {
            // 响应已按 text/event-stream 提交：再抛错只会触发 "No converter for LinkedHashMap"
            // 二次噪音且端侧同样收不到错误体，干净收尾即可（票 33）
            relay.emitter.complete();
        }
    }

    private enum RelayEnd {
        COMPLETE("complete"),
        TIMEOUT("timeout"),
        UPSTREAM_FAILED("upstream failed"),
        DOWNSTREAM_BROKEN("downstream broken"),
        FORWARD_FAILED("forward failed");

        private final String label;

        RelayEnd(String label) {
            this.label = label;
        }
    }

    /** 单条 chat 中继的生命周期状态：emitter、事件计数与终结幂等。 */
    private static final class RelayState {
        private final SseEmitter emitter;
        private final Long conversationId;
        private final long startedNanos = System.nanoTime();
        private final AtomicInteger forwarded = new AtomicInteger();
        private final AtomicBoolean terminated = new AtomicBoolean();

        private RelayState(SseEmitter emitter, Long conversationId) {
            this.emitter = emitter;
            this.conversationId = conversationId;
        }

        private long costMs() {
            return (System.nanoTime() - startedNanos) / 1_000_000;
        }
    }

    private JsonNode parseData(String data) throws JsonProcessingException {
        return data == null || data.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(data);
    }

    private void send(SseEmitter emitter, String event, JsonNode data) throws IOException {
        SseEmitter.SseEventBuilder builder = SseEmitter.event();
        if (event != null) {
            builder.name(event);
        }
        emitter.send(builder.data(objectMapper.writeValueAsString(data)));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }
}
