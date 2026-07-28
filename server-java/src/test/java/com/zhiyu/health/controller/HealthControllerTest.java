package com.zhiyu.health.controller;

import com.zhiyu.health.service.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 层 seam：只测外部行为；AuthFilter 不拦 /api/health，无需令牌 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    void healthReturnsOkWhenAllServicesUp() throws Exception {
        when(healthService.check()).thenReturn(Map.of(
                "status", "ok",
                "services", Map.of(
                        "postgres", Map.of("status", "ok"),
                        "redis", Map.of("status", "ok"))));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.services.postgres.status").value("ok"));
    }

    @Test
    void healthReturnsDegradedWhenPostgresDown() throws Exception {
        when(healthService.check()).thenReturn(Map.of(
                "status", "degraded",
                "services", Map.of(
                        "postgres", Map.of("status", "error"),
                        "redis", Map.of("status", "ok"))));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.services.postgres.status").value("error"));
    }
}
