package com.zhiyu.health.controller.c;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.ChatService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** C 端对话接口：只做参数校验与装配，业务在 ChatService */
@RestController
@RequestMapping("/api/c")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    public record ChatRequest(
            @NotBlank String content,
            @JsonProperty("conversation_id") Long conversationId,
            String effort,
            String scenario,
            // 用户授权定位后回传的经纬度；拒绝授权时不传，由 Agent 降级提示手动选区
            @JsonProperty("longitude") @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @JsonProperty("latitude") @DecimalMin("-90") @DecimalMax("90") Double latitude) {}

    @PostMapping("/chat")
    public SseEmitter chat(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId,
            @Validated @RequestBody ChatRequest request) {
        return chatService.chat(
                patientId,
                request.conversationId(),
                request.content(),
                request.effort(),
                request.scenario(),
                request.longitude(),
                request.latitude());
    }
}
