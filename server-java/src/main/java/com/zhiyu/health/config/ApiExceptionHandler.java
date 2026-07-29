package com.zhiyu.health.config;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一错误出口：形状由 ApiErrorBody 定型，与过滤器直写及票 02 Python 原件的错误契约一致 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        Map<String, Object> body =
                e.getCode() == null ? ApiErrorBody.of(e.getMessage()) : ApiErrorBody.of(e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    /** @RequestParam 上的 @Validated 校验失败：与 @RequestBody 校验一致返回 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(ApiErrorBody.of(e.getMessage()));
    }
}
