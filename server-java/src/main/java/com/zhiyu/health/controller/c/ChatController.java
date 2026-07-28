package com.zhiyu.health.controller.c;

import com.zhiyu.health.service.ChatService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** C 端对话接口：只做参数校验与装配，业务在 ChatService */
@RestController
@RequestMapping("/api/c")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record ChatRequest(
            @NotBlank String content,
            @JsonProperty("conversation_id") Long conversationId,
            String effort,
            String scenario) {
    }

    @PostMapping("/chat")
    public SseEmitter chat(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) String patientId,
            @Validated @RequestBody ChatRequest request) {
        return chatService.chat(
                Long.valueOf(patientId), request.conversationId(), request.content(),
                request.effort(), request.scenario());
    }
}
