package com.zhiyu.health.controller;

import com.zhiyu.health.service.ConversationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 会话领域异常到稳定 HTTP 契约的映射。 */
@RestControllerAdvice
public class ConversationExceptionHandler {

    @ExceptionHandler(ConversationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> conversationNotFound() {
        return Map.of("detail", "会话不存在");
    }
}
