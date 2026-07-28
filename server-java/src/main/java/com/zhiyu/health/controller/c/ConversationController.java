package com.zhiyu.health.controller.c;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.ConversationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** C 端会话消息读取；只返回当前患者拥有的会话。 */
@RestController
@RequestMapping("/api/c/conversations")
public class ConversationController {

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageOut(
            Long id,
            String role,
            String kind,
            String content,
            String effort,
            String disclaimer,
            String createdAt) {
    }

    @GetMapping("/{conversationId}/messages")
    public List<MessageOut> listMessages(
            @PathVariable Long conversationId,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) String patientId) {
        return conversations.listMessagesForPatient(conversationId, Long.valueOf(patientId)).stream()
                .map(message -> new MessageOut(
                        message.id(), message.role(), message.kind(), message.content(),
                        message.effort(), message.disclaimer(), message.createdAt()))
                .toList();
    }
}
