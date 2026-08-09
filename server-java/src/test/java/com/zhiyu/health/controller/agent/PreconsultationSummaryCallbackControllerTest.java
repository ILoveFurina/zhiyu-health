package com.zhiyu.health.controller.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.AgentCallbackAuthFilter;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.service.chat.PreconsultationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** 预问诊摘要异步回调（票 55 改造）：Agent 凭证保护、摘要 payload 落草稿、降级语义。 */
class PreconsultationSummaryCallbackControllerTest {

    private static final String SECRET = "shared-secret";

    private final PreconsultationService preconsultationService = mock(PreconsultationService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private MockMvc mvc() {
        return standaloneSetup(new PreconsultationSummaryCallbackController(preconsultationService))
                .addFilter(new AgentCallbackAuthFilter(SECRET))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void callbackRequiresSharedServiceCredential() throws Exception {
        mvc().perform(post("/api/agent/preconsultation-drafts/5/summary")
                        .contentType("application/json")
                        .content("{\"chief_complaint\":\"咳嗽\"}"))
                .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/agent/preconsultation-drafts/5/summary")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, "wrong")
                        .contentType("application/json")
                        .content("{\"chief_complaint\":\"咳嗽\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void summaryPayloadDelegatesToApplySummaryAndReturnsNoContent() throws Exception {
        String payload =
                """
                {"chief_complaint":"咳嗽三天","present_illness":"干咳无痰","allergy_history":"无","suggested_standard_department_id":2}""";

        mvc().perform(post("/api/agent/preconsultation-drafts/5/summary")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, SECRET)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isNoContent());

        verify(preconsultationService).applySummary(eq(5L), eq(mapper.readTree(payload)));
    }

    @Test
    void applySummaryFailurePropagatesAsError() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(409, "草稿已提交"))
                .when(preconsultationService)
                .applySummary(eq(5L), any());

        mvc().perform(post("/api/agent/preconsultation-drafts/5/summary")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, SECRET)
                        .contentType("application/json")
                        .content("{\"chief_complaint\":\"咳嗽\"}"))
                .andExpect(status().isConflict());
    }
}
