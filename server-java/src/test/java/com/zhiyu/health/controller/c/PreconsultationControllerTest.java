package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.service.PreconsultationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 票 54 预问诊草稿 HTTP seam：开始/恢复、无档案 409、归属 404。 */
class PreconsultationControllerTest {

    @Test
    void startOrResumeReturnsDraftWithSummaryShape() throws Exception {
        PreconsultationService service = mock(PreconsultationService.class);
        when(service.startOrResume(12L)).thenReturn(draftView());
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/preconsultation-drafts").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.id").value(5))
                .andExpect(jsonPath("$.draft.status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$.draft.status_label").value("待确认"))
                .andExpect(jsonPath("$.draft.conversation_id").value(77))
                .andExpect(jsonPath("$.draft.health_profile_id").value(3))
                .andExpect(jsonPath("$.draft.summary.chief_complaint").value("咳嗽三天"))
                .andExpect(jsonPath("$.draft.summary.present_illness").value("干咳无痰"))
                .andExpect(jsonPath("$.draft.summary.allergy_history").value("无"))
                .andExpect(jsonPath("$.draft.summary.disclaimer").value("仅供参考，不替代医生诊断"))
                .andExpect(jsonPath("$.draft.summary.suggested_standard_department_id")
                        .value(2))
                .andExpect(jsonPath("$.draft.summary.suggested_standard_department_name")
                        .value("呼吸内科"))
                .andExpect(jsonPath("$.draft.summary.updated_at").value("2026-08-07T10:00:00+08:00"))
                .andExpect(jsonPath("$.draft.current_consultation_id").value(21))
                .andExpect(jsonPath("$.draft.created_at").value("2026-08-07T09:00:00+08:00"));
        verify(service).startOrResume(12L);
    }

    @Test
    void startWithoutActiveProfileYields409() throws Exception {
        PreconsultationService service = mock(PreconsultationService.class);
        when(service.startOrResume(12L)).thenThrow(new ApiException(409, "请先创建健康档案并选择当前服务对象"));
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/preconsultation-drafts").requestAttr("authSubject", 12L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请先创建健康档案并选择当前服务对象"));
    }

    @Test
    void getReturnsOwnedDraft() throws Exception {
        PreconsultationService service = mock(PreconsultationService.class);
        when(service.get(12L, 5L)).thenReturn(draftView());
        MockMvc mvc = standalone(service);

        mvc.perform(get("/api/c/preconsultation-drafts/5").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.id").value(5));
        verify(service).get(12L, 5L);
    }

    @Test
    void getForeignOrMissingDraftYields404() throws Exception {
        PreconsultationService service = mock(PreconsultationService.class);
        doThrow(new ApiException(404, "预问诊草稿不存在")).when(service).get(12L, 5L);
        MockMvc mvc = standalone(service);

        mvc.perform(get("/api/c/preconsultation-drafts/5").requestAttr("authSubject", 12L))
                .andExpect(status().isNotFound());
    }

    private MockMvc standalone(PreconsultationService service) {
        return MockMvcBuilders.standaloneSetup(new PreconsultationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private PreconsultationService.DraftView draftView() {
        PreconsultationService.DraftSummaryView summary = new PreconsultationService.DraftSummaryView(
                "咳嗽三天", "干咳无痰", "无", "仅供参考，不替代医生诊断", 2L, "呼吸内科", "2026-08-07T10:00:00+08:00");
        return new PreconsultationService.DraftView(
                5L, "PENDING_CONFIRM", "待确认", 77L, 3L, summary, 21L, "2026-08-07T09:00:00+08:00");
    }
}
