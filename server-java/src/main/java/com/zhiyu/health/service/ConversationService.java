package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.mapper.ConversationMapper;
import com.zhiyu.health.mapper.MessageMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 会话与消息持久化；业务后端是唯一写入方。 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    public static final int TITLE_MAX_LENGTH = 20;
    private static final int CONTEXT_MESSAGE_LIMIT = 20;
    /** 对话记录列表硬上限：按最近活跃倒序，不做分页（见票 27 决策 1）。 */
    private static final int LIST_LIMIT = 50;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    @Transactional
    public Conversation getOrCreateForPatient(Long patientId, Long conversationId, String firstText) {
        if (conversationId != null) {
            Conversation found = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getId, conversationId)
                    .eq(Conversation::getPatientId, patientId));
            if (found == null) {
                throw notFound();
            }
            return found;
        }
        String title = firstText.substring(0, Math.min(firstText.length(), TITLE_MAX_LENGTH));
        Conversation conversation = new Conversation(null, patientId, title);
        conversationMapper.insert(conversation);
        return conversation;
    }

    public Conversation getForPatient(Long conversationId, Long patientId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getPatientId, patientId));
    }

    /** 当前患者的对话记录列表；最近活跃倒序，硬上限 50 条，只返三字段（见票 27 决策 1/12）。 */
    public List<ConversationSummary> listForPatient(Long patientId) {
        return conversationMapper
                .selectList(new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getPatientId, patientId)
                        .orderByDesc(Conversation::getLastActiveAt)
                        .last("LIMIT " + LIST_LIMIT))
                .stream()
                .map(c -> new ConversationSummary(
                        c.getId(),
                        c.getTitle(),
                        c.getLastActiveAt() == null ? null : c.getLastActiveAt().toString()))
                .toList();
    }

    /**
     * 硬删会话：DELETE 同时限定 id 与 patient_id（幂等、并发安全，见票 27 决策 2/9）。
     * 归属/不存在一律 404，不区分原因以免泄露存在性（决策 3）。依赖 messages FK
     * ON DELETE CASCADE 连带删消息、appointments FK ON DELETE SET NULL 保留挂号单。
     */
    @Transactional
    public void deleteForPatient(Long conversationId, Long patientId) {
        if (getForPatient(conversationId, patientId) == null) {
            throw notFound();
        }
        conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getPatientId, patientId));
    }

    /** 当前患者的消息输出；归属校验与免责声明语义都在业务层完成。 */
    public List<MessageView> listMessagesForPatient(Long conversationId, Long patientId) {
        if (getForPatient(conversationId, patientId) == null) {
            throw notFound();
        }
        return listMessages(conversationId).stream()
                .filter(message -> !Message.KIND_REPORT_CONTEXT.equals(message.getKind()))
                .map(message -> new MessageView(
                        message.getId(),
                        message.getRole(),
                        message.getKind(),
                        message.getContent(),
                        message.getEffort(),
                        isAiOutput(message) ? ChatService.DISCLAIMER : null,
                        message.getCreatedAt() == null
                                ? null
                                : message.getCreatedAt().toString()))
                .toList();
    }

    @Transactional
    public Message appendMessage(Long conversationId, String role, String content, String kind, String effort) {
        return appendMessage(conversationId, role, content, kind, effort, null);
    }

    @Transactional
    public Message appendMessage(
            Long conversationId, String role, String content, String kind, String effort, Long reportInterpretationId) {
        Message message = new Message(null, conversationId, role, kind, content, effort);
        message.setReportInterpretationId(reportInterpretationId);
        messageMapper.insert(message);
        conversationMapper.update(
                null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getId, conversationId)
                        .set(Conversation::getLastActiveAt, OffsetDateTime.now()));
        return message;
    }

    public List<Message> listMessages(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getId));
    }

    public List<Map<String, String>> recentContext(Long conversationId) {
        List<Message> newestFirst = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                // 卡片 JSON 用于历史渲染，不是自然语言，避免重复塞回 LLM 上下文。
                .notIn(
                        Message::getKind,
                        Message.KIND_DOCTOR_RECOMMENDATIONS,
                        Message.KIND_DOCTOR_SLOTS,
                        Message.KIND_HOSPITAL_RECOMMENDATIONS,
                        Message.KIND_APPOINTMENT,
                        Message.KIND_APPOINTMENTS,
                        Message.KIND_REPORT_UPLOAD,
                        Message.KIND_REPORT_INTERPRETATION)
                .orderByDesc(Message::getId)
                .last("LIMIT " + CONTEXT_MESSAGE_LIMIT));
        List<Message> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return chronological.stream()
                .map(message -> Map.of("role", message.getRole(), "content", message.getContent()))
                .toList();
    }

    private boolean isAiOutput(Message message) {
        return "assistant".equals(message.getRole())
                && (Message.KIND_TEXT.equals(message.getKind()) || Message.isAiCardKind(message.getKind()));
    }

    /** 归属/不存在一律 404，不区分原因以免泄露存在性（对齐票 27 决策 3）。 */
    private static ApiException notFound() {
        return new ApiException(404, "会话不存在");
    }

    public record MessageView(
            Long id, String role, String kind, String content, String effort, String disclaimer, String createdAt) {}

    /** 对话记录列表项；严格三字段，不加预览（见票 27 决策 11/12）。 */
    public record ConversationSummary(Long id, String title, String lastActiveAt) {}
}
