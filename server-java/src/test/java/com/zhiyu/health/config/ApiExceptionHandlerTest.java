package com.zhiyu.health.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void 参数错误统一为稳定四百结构() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleConstraintViolation(new ConstraintViolationException("敏感原始参数", java.util.Set.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(ApiErrorBody.of("INVALID_REQUEST", "请求参数不合法"));
    }

    @Test
    void 未知异常不向客户端暴露原始消息() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(new RuntimeException("jdbc:secret"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isEqualTo(ApiErrorBody.of("INTERNAL_ERROR", "服务暂不可用，请稍后重试"));
        assertThat(response.getBody().toString()).doesNotContain("jdbc:secret");
    }
}
