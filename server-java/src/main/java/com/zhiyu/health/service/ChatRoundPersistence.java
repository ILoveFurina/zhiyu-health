package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.ChatRound;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.mapper.ChatRoundMapper;
import com.zhiyu.health.mapper.MessageMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 对话轮次及其消息的 PostgreSQL 一致性边界。 */
@Service
@RequiredArgsConstructor
public class ChatRoundPersistence {

    private final ChatRoundMapper roundMapper;
    private final MessageMapper messageMapper;
    private final ConversationService conversations;
    private final DisclaimerService disclaimers;
    private final Contracts contracts;
    private final ObjectMapper objectMapper;

    public ChatRound find(Long patientId, String requestId) {
        return roundMapper.selectOne(new LambdaQueryWrapper<ChatRound>()
                .eq(ChatRound::getPatientId, patientId)
                .eq(ChatRound::getRequestId, requestId));
    }

    @Transactional
    public ChatRound create(Long patientId, String requestId, Long conversationId, String content) {
        Conversation conversation = conversations.getOrCreateForPatient(patientId, conversationId, content);
        Message user = conversations.appendMessage(conversation.getId(), "user", content, Message.KIND_TEXT, null);
        ChatRound round = new ChatRound(
                patientId,
                requestId,
                conversation.getId(),
                user.getId(),
                contracts.chatRealtime().acceptedStatus());
        roundMapper.insert(round);
        return round;
    }

    public List<Map<String, String>> recentContext(Long conversationId) {
        return conversations.recentContext(conversationId);
    }

    public Message finalMessage(ChatRound round) {
        return round.getAssistantMessageId() == null ? null : messageMapper.selectById(round.getAssistantMessageId());
    }

    @Transactional
    public void markRunning(Long roundId) {
        update(roundId, contracts.chatRealtime().runningStatus(), null, OffsetDateTime.now(), null, null);
    }

    /** 出口兜底、消息落库与事件增强在同一事务完成，随后才允许实时下发。 */
    @Transactional
    public JsonNode persistEvent(ChatRound round, String eventName, JsonNode input) {
        JsonNode data = input == null ? objectMapper.createObjectNode() : input.deepCopy();
        Contracts.SseEvents sse = contracts.sseEvents();
        if (data instanceof ObjectNode object) {
            object.put("request_id", round.getRequestId());
            if (sse.metaEvent().equals(eventName)) {
                object.put("conversation_id", round.getConversationId());
            } else if (sse.messageEvent().equals(eventName)) {
                disclaimers.mount(object);
                Message saved = conversations.appendMessage(
                        round.getConversationId(),
                        "assistant",
                        object.path("content").asText(),
                        Message.KIND_TEXT,
                        nullableText(object.get("effort")));
                object.put("message_id", saved.getId());
                round.setAssistantMessageId(saved.getId());
                roundMapper.update(
                        null,
                        new LambdaUpdateWrapper<ChatRound>()
                                .eq(ChatRound::getId, round.getId())
                                .set(ChatRound::getAssistantMessageId, saved.getId()));
            } else if (Message.isAiCardKind(eventName)) {
                disclaimers.mount(object);
                try {
                    Message saved = conversations.appendMessage(
                            round.getConversationId(),
                            "assistant",
                            objectMapper.writeValueAsString(object),
                            eventName,
                            null);
                    object.put("message_id", saved.getId());
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Agent 卡片序列化失败", e);
                }
            }
        }
        return data;
    }

    @Transactional
    public Message completeRedFlag(ChatRound round, String warning) {
        Message saved = conversations.appendMessage(round.getConversationId(), "assistant", warning, "red_flag", null);
        round.setAssistantMessageId(saved.getId());
        update(
                round.getId(),
                contracts.chatRealtime().completedStatus(),
                null,
                null,
                OffsetDateTime.now(),
                saved.getId());
        return saved;
    }

    @Transactional
    public void markCompleted(Long roundId) {
        update(roundId, contracts.chatRealtime().completedStatus(), null, null, OffsetDateTime.now(), null);
    }

    @Transactional
    public void markFailed(Long roundId, String errorCode) {
        update(roundId, contracts.chatRealtime().failedStatus(), errorCode, null, OffsetDateTime.now(), null);
    }

    private void update(
            Long roundId,
            String status,
            String errorCode,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            Long assistantMessageId) {
        LambdaUpdateWrapper<ChatRound> update = new LambdaUpdateWrapper<ChatRound>()
                .eq(ChatRound::getId, roundId)
                .set(ChatRound::getStatus, status);
        if (errorCode != null) {
            update.set(ChatRound::getErrorCode, errorCode);
        }
        if (startedAt != null) {
            update.set(ChatRound::getStartedAt, startedAt);
        }
        if (completedAt != null) {
            update.set(ChatRound::getCompletedAt, completedAt);
        }
        if (assistantMessageId != null) {
            update.set(ChatRound::getAssistantMessageId, assistantMessageId);
        }
        roundMapper.update(null, update);
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }
}
