package com.zhiyu.health.config;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 统一错误出口：形状由 ApiErrorBody 定型，与过滤器直写及票 02 Python 原件的错误契约一致 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        Map<String, Object> body =
                e.getCode() == null ? ApiErrorBody.of(e.getMessage()) : ApiErrorBody.of(e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    /** @RequestParam 上的 @Validated 校验失败：与 @RequestBody 校验一致返回 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException e) {
        return badRequest();
    }

    /** 请求体字段、表单绑定和 JSON 解析错误统一为稳定 400，不向客户端暴露框架内部细节。 */
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        BindException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> handleInvalidInput(Exception e) {
        return badRequest();
    }

    /** 最终兜底只记录异常类型；异常消息可能含 SQL、连接信息或患者原文，禁止进入日志和响应。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("unhandled request exception type={}", e.getClass().getName());
        return ResponseEntity.internalServerError().body(ApiErrorBody.of("INTERNAL_ERROR", "服务暂不可用，请稍后重试"));
    }

    private ResponseEntity<Map<String, Object>> badRequest() {
        return ResponseEntity.badRequest().body(ApiErrorBody.of("INVALID_REQUEST", "请求参数不合法"));
    }
}
