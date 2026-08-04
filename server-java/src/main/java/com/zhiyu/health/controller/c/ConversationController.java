package com.zhiyu.health.controller.c;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.ConversationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端会话消息读取与对话记录管理；只返回当前患者拥有的会话。 */
@RestController
@RequestMapping("/api/c/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversations;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageOut(
            Long id,
            String role,
            String kind,
            String content,
            String effort,
            String emotion,
            String disclaimer,
            String createdAt) {}

    /** 对话记录列表项；严格三字段（见票 27 决策 12）。 */
    public record ConversationOut(Long id, String title, @JsonProperty("last_active_at") String lastActiveAt) {}

    @GetMapping
    public List<ConversationOut> list(@RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return conversations.listForPatient(patientId).stream()
                .map(s -> new ConversationOut(s.id(), s.title(), s.lastActiveAt()))
                .toList();
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long conversationId, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        conversations.deleteForPatient(conversationId, patientId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{conversationId}/messages")
    public List<MessageOut> listMessages(
            @PathVariable Long conversationId, @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId) {
        return conversations.listMessagesForPatient(conversationId, patientId).stream()
                .map(message -> new MessageOut(
                        message.id(),
                        message.role(),
                        message.kind(),
                        message.content(),
                        message.effort(),
                        message.emotion(),
                        message.disclaimer(),
                        message.createdAt()))
                .toList();
    }
}
