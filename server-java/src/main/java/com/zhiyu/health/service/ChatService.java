package com.zhiyu.health.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.rule.RedFlagHit;
import com.zhiyu.health.rule.RedFlagRuleEngine;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 对话主干：持久化 → 红线前置 → Agent SSE → 出口兜底。 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final long EMITTER_TIMEOUT_MS = 60_000L;
    private static final String RED_FLAG_TEMPLATE = "检测到紧急危险信号：%s。%s。导诊已中断。";

    private final AgentClient agentClient;
    private final ConversationService conversations;
    private final RedFlagRuleEngine redFlagRules;
    private final ObjectMapper objectMapper;
    private final DisclaimerService disclaimers;

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
        Message saved = conversations.appendMessage(conversation.getId(), "assistant", warning, "red_flag", null);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        try {
            send(emitter, "meta", objectMapper.createObjectNode().put("conversation_id", conversation.getId()));
            ObjectNode data = objectMapper
                    .createObjectNode()
                    .put("message_id", saved.getId())
                    .put("rule", hit.ruleName())
                    .put("content", warning)
                    .put("advice", hit.advice());
            send(emitter, "red_flag", data);
            send(emitter, "done", objectMapper.createObjectNode());
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
        body.put("effort", blankToDefault(effort, "auto"));
        body.put("scenario", blankToDefault(scenario, "triage"));
        // 经纬度来自用户授权定位；拒绝授权时不传，server-py 的 find_hospitals 据此降级。
        if (longitude != null && latitude != null) {
            body.put("longitude", longitude);
            body.put("latitude", latitude);
        }

        agentClient
                .chat(body)
                .subscribe(
                        event -> forwardAgentEvent(emitter, conversation.getId(), event),
                        emitter::completeWithError,
                        emitter::complete);
        return emitter;
    }

    private void forwardAgentEvent(SseEmitter emitter, Long conversationId, ServerSentEvent<String> event) {
        try {
            String eventName = event.event();
            JsonNode data = parseData(event.data());
            if ("meta".equals(eventName) && data instanceof ObjectNode object) {
                object.put("conversation_id", conversationId);
            } else if ("message".equals(eventName) && data instanceof ObjectNode object) {
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
            send(emitter, eventName, data);
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
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
