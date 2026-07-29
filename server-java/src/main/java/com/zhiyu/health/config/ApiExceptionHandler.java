package com.zhiyu.health.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误体 {"detail": "..."}，与 AuthFilter 及票 02 Python 原件的错误形状一致 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        Object detail = e.getCode() == null
                ? e.getMessage()
                : Map.of("code", e.getCode(), "message", e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(Map.of("detail", detail));
    }
}
