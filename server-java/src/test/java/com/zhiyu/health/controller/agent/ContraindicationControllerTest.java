package com.zhiyu.health.controller.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.AgentCallbackAuthFilter;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.agent.mapping.ContraindicationDtoMapper;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.ContraindicationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ContraindicationController.class)
class ContraindicationControllerTest {

    private static final String CALLBACK_SECRET = "test-only-agent-callback-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContraindicationService service;

    @MockitoBean
    private ContraindicationDtoMapper dtoMapper;

    @BeforeEach
    void mapRequestsToTrustedRuntimeCommands() {
        when(dtoMapper.toCommand(any())).thenAnswer(invocation -> {
            ContraindicationController.CheckRequest request = invocation.getArgument(0);
            return new ContraindicationService.CheckCommand(request.patientId(), request.medicationIds());
        });
    }

    @Test
    void returnsDeterministicWarningCard() throws Exception {
        ContraindicationResult result = new ContraindicationResult(
                "BLOCKED",
                "contraindication_warning",
                true,
                List.of("过敏史“青霉素”与药品 1 的成分/禁忌项匹配"),
                "检测到用药禁忌，已阻止本次药品推荐。请咨询医生或药师后再用药。",
                "请咨询医生或药师，并主动告知完整过敏史和正在使用的药品。");
        when(service.check(new ContraindicationService.CheckCommand(12L, List.of(1L))))
                .thenReturn(result);
        when(dtoMapper.toResponse(result))
                .thenReturn(new ContraindicationController.CheckResponse(
                        result.decision(),
                        result.messageType(),
                        result.blocked(),
                        result.reasons(),
                        result.message(),
                        result.advice()));

        mockMvc.perform(post("/api/agent/contraindications/check")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, CALLBACK_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patient_id\":12,\"medication_ids\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("BLOCKED"))
                .andExpect(jsonPath("$.message_type").value("contraindication_warning"))
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.reasons[0]").value("过敏史“青霉素”与药品 1 的成分/禁忌项匹配"));
    }

    @Test
    void rejectsCallsWithoutAgentCredential() throws Exception {
        mockMvc.perform(post("/api/agent/contraindications/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patient_id\":12,\"medication_ids\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportsMissingCurrentProfile() throws Exception {
        when(service.check(new ContraindicationService.CheckCommand(12L, List.of(1L))))
                .thenThrow(new ApiException(409, "请先创建并激活健康档案后再进行禁忌检查"));

        mockMvc.perform(post("/api/agent/contraindications/check")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, CALLBACK_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patient_id\":12,\"medication_ids\":[1]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请先创建并激活健康档案后再进行禁忌检查"));
    }

    @Test
    void reportsUnknownMedicationId() throws Exception {
        when(service.check(new ContraindicationService.CheckCommand(12L, List.of(999L))))
                .thenThrow(new ApiException(400, "药品不存在或已停用: 999"));

        mockMvc.perform(post("/api/agent/contraindications/check")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, CALLBACK_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patient_id\":12,\"medication_ids\":[999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("药品不存在或已停用: 999"));
    }
}
