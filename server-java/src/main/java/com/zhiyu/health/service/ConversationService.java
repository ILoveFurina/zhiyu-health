package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhiyu.health.entity.Conversation;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.mapper.ConversationMapper;
import com.zhiyu.health.mapper.MessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 会话与消息持久化；业务后端是唯一写入方。 */
@Service
public class ConversationService {

    public static final int TITLE_MAX_LENGTH = 20;
    private static final int CONTEXT_MESSAGE_LIMIT = 20;
    /** 对话记录列表硬上限：按最近活跃倒序，不做分页（见票 27 决策 1）。 */
    private static final int LIST_LIMIT = 50;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationService(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public Conversation getOrCreateForPatient(Long patientId, Long conversationId, String firstText) {
        if (conversationId != null) {
            Conversation found = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getId, conversationId)
                    .eq(Conversation::getPatientId, patientId));
            if (found == null) {
                throw new ConversationNotFoundException();
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
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getPatientId, patientId)
                        .orderByDesc(Conversation::getLastActiveAt)
                        .last("LIMIT " + LIST_LIMIT)).stream()
                .map(c -> new ConversationSummary(
                        c.getId(),
                        c.getTitle(),
                        c.getLastActiveAt() == null ? null : c.getLastActiveAt().toString()))
                .toList();
    }

    /**
     * 硬删会话：依赖 messages FK ON DELETE CASCADE 连带删消息、appointments FK ON DELETE SET NULL
     * 保留挂号单（见票 27 决策 2）。归属/不存在一律 404，不区分原因以免泄露存在性（决策 3）。
     */
    @Transactional
    public void deleteForPatient(Long conversationId, Long patientId) {
        if (getForPatient(conversationId, patientId) == null) {
            throw new ConversationNotFoundException();
        }
        conversationMapper.deleteById(conversationId);
    }

    /** 当前患者的消息输出；归属校验与免责声明语义都在业务层完成。 */
    public List<MessageView> listMessagesForPatient(Long conversationId, Long patientId) {
        if (getForPatient(conversationId, patientId) == null) {
            throw new ConversationNotFoundException();
        }
        return listMessages(conversationId).stream()
                .map(message -> new MessageView(
                        message.getId(),
                        message.getRole(),
                        message.getKind(),
                        message.getContent(),
                        message.getEffort(),
                        isAiOutput(message) ? ChatService.DISCLAIMER : null,
                        message.getCreatedAt() == null ? null : message.getCreatedAt().toString()))
                .toList();
    }

    @Transactional
    public Message appendMessage(Long conversationId, String role, String content,
                                 String kind, String effort) {
        Message message = new Message(null, conversationId, role, kind, content, effort);
        messageMapper.insert(message);
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
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
                .notIn(Message::getKind,
                        Message.KIND_DOCTOR_RECOMMENDATIONS, Message.KIND_DOCTOR_SLOTS,
                        Message.KIND_APPOINTMENT, Message.KIND_APPOINTMENTS)
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
                && (Message.KIND_TEXT.equals(message.getKind())
                || Message.isAiCardKind(message.getKind()));
    }

    public record MessageView(
            Long id,
            String role,
            String kind,
            String content,
            String effort,
            String disclaimer,
            String createdAt) {
    }

    /** 对话记录列表项；严格三字段，不加预览（见票 27 决策 11/12）。 */
    public record ConversationSummary(
            Long id,
            String title,
            String lastActiveAt) {
    }
}
