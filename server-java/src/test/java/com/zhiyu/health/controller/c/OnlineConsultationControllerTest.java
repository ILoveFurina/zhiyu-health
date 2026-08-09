package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.controller.patient.consultation.OnlineConsultationController;
import com.zhiyu.health.service.consultation.OnlineConsultationService;
import com.zhiyu.health.service.consultation.OnlineConsultationViews;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 票 55 在线问诊 C 端 HTTP seam：建单/查询/取消/重新提交/医患消息的归属与状态负向路径。 */
class OnlineConsultationControllerTest {

    @Test
    void confirmReturnsConsultationDetail() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.confirm(12L, 5L)).thenReturn(detail("WAITING_DOCTOR"));
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/online-consultations")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"draft_id\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultation.id").value(21))
                .andExpect(jsonPath("$.consultation.status").value("WAITING_DOCTOR"))
                .andExpect(jsonPath("$.consultation.status_label").value("等待医生接诊"))
                .andExpect(jsonPath("$.consultation.progress_step").value("WAITING_DOCTOR"))
                .andExpect(jsonPath("$.consultation.standard_department_id").value(2))
                .andExpect(jsonPath("$.consultation.standard_department_name").value("呼吸内科"))
                .andExpect(jsonPath("$.consultation.summary.chief_complaint").value("咳嗽三天"))
                .andExpect(jsonPath("$.consultation.summary_disclaimer").value("仅供参考，不替代医生诊断"))
                .andExpect(jsonPath("$.consultation.expires_at").value("2026-08-07T10:10:00+08:00"))
                .andExpect(jsonPath("$.consultation.doctor").doesNotExist())
                .andExpect(jsonPath("$.consultation.terminal_hint").doesNotExist());
        verify(service).confirm(12L, 5L);
    }

    @Test
    void confirmRequiresDraftId() throws Exception {
        MockMvc mvc = standalone(mock(OnlineConsultationService.class));

        mvc.perform(post("/api/c/online-consultations")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmWithoutSummaryOrDepartmentYields409() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.confirm(12L, 5L)).thenThrow(new ApiException(409, "请先与 AI 完成预问诊病情摘要"));
        when(service.confirm(12L, 6L)).thenThrow(new ApiException(409, "请继续完善预问诊信息，暂未确定建议科室"));
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/online-consultations")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"draft_id\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请先与 AI 完成预问诊病情摘要"));
        mvc.perform(post("/api/c/online-consultations")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"draft_id\":6}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请继续完善预问诊信息，暂未确定建议科室"));
    }

    @Test
    void confirmForeignDraftYields404() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.confirm(12L, 5L)).thenThrow(new ApiException(404, "预问诊草稿不存在"));
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/online-consultations")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"draft_id\":5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsMine() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.listMine(12L))
                .thenReturn(List.of(new OnlineConsultationViews.ConsultationListItem(
                        21L,
                        "WAITING_DOCTOR",
                        "等待医生接诊",
                        7L,
                        "呼吸内科",
                        null,
                        "2026-08-07T10:00:00+08:00",
                        "2026-08-07T10:10:00+08:00")));
        MockMvc mvc = standalone(service);

        mvc.perform(get("/api/c/online-consultations").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations[0].id").value(21))
                .andExpect(jsonPath("$.consultations[0].status_label").value("等待医生接诊"))
                .andExpect(
                        jsonPath("$.consultations[0].standard_department_name").value("呼吸内科"));
        verify(service).listMine(12L);
    }

    @Test
    void detailForeignYields404() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.detail(12L, 21L)).thenThrow(new ApiException(404, "问诊单不存在"));
        MockMvc mvc = standalone(service);

        mvc.perform(get("/api/c/online-consultations/21").requestAttr("authSubject", 12L))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelReturnsCancelledConsultationAndRepeatedCancelStaysOk() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.cancel(12L, 21L)).thenReturn(detail("CANCELLED"));
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/online-consultations/21/cancel").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultation.status").value("CANCELLED"))
                .andExpect(jsonPath("$.consultation.progress_step").doesNotExist())
                .andExpect(jsonPath("$.consultation.terminal_hint").value("问诊已取消。可复用原病情摘要重新提交，无需重复预问诊。"));
    }

    @Test
    void cancelNonWaitingYields409() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        doThrow(new ApiException(409, "问诊单不在等待接诊状态")).when(service).cancel(12L, 21L);
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/online-consultations/21/cancel").requestAttr("authSubject", 12L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("问诊单不在等待接诊状态"));
    }

    @Test
    void resubmitReturnsNewConsultation() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.resubmit(12L, 21L)).thenReturn(detail("WAITING_DOCTOR"));
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/online-consultations/21/resubmit").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultation.status").value("WAITING_DOCTOR"));
        verify(service).resubmit(12L, 21L);
    }

    @Test
    void resubmitActiveConsultationYields409() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        doThrow(new ApiException(409, "仅已取消或已失效的问诊可重新提交")).when(service).resubmit(12L, 21L);
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/online-consultations/21/resubmit").requestAttr("authSubject", 12L))
                .andExpect(status().isConflict());
    }

    @Test
    void messagesIncrementallyListedAndSent() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.listMessagesForPatient(12L, 21L, 40L)).thenReturn(List.of(message(41L, "DOCTOR", "先多喝水休息")));
        when(service.sendMessageForPatient(12L, 21L, "好的谢谢医生")).thenReturn(message(42L, "PATIENT", "好的谢谢医生"));
        MockMvc mvc = standalone(service);

        mvc.perform(get("/api/c/online-consultations/21/messages?after_id=40").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].id").value(41))
                .andExpect(jsonPath("$.messages[0].sender_type").value("DOCTOR"))
                .andExpect(jsonPath("$.messages[0].content").value("先多喝水休息"));

        mvc.perform(post("/api/c/online-consultations/21/messages")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"content\":\"好的谢谢医生\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.sender_type").value("PATIENT"));
        verify(service).listMessagesForPatient(12L, 21L, 40L);
        verify(service).sendMessageForPatient(12L, 21L, "好的谢谢医生");
    }

    @Test
    void sendMessageRequiresInProgress() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        doThrow(new ApiException(409, "问诊不在进行中")).when(service).sendMessageForPatient(12L, 21L, "你好");
        MockMvc mvc = standalone(service);

        mvc.perform(post("/api/c/online-consultations/21/messages")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"content\":\"你好\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("问诊不在进行中"));
    }

    @Test
    void sendMessageRequiresNonBlankContent() throws Exception {
        MockMvc mvc = standalone(mock(OnlineConsultationService.class));

        mvc.perform(post("/api/c/online-consultations/21/messages")
                        .requestAttr("authSubject", 12L)
                        .contentType("application/json")
                        .content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendPhotoUploadsAndReturnsImageMessage() throws Exception {
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        when(service.sendImageForPatient(eq(12L), eq(21L), any()))
                .thenReturn(imageMessage(
                        43L, "{\"object_key\":\"photos/2026-08-08/abc.jpg\",\"media_type\":\"image/jpeg\"}"));
        MockMvc mvc = standalone(service);

        mvc.perform(multipart("/api/c/online-consultations/21/photos")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1}))
                        .requestAttr("authSubject", 12L))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.sender_type").value("PATIENT"))
                .andExpect(jsonPath("$.message.kind").value("image"));
        verify(service).sendImageForPatient(eq(12L), eq(21L), any());
    }

    @Test
    void sendPhotoDelegatesOversizeToService() throws Exception {
        // controller 不再重复校验图片大小/格式（与拍药盒一致），全委托 service；
        // service 抛 400 时 controller 经 @ControllerAdvice 透传给客户端。
        OnlineConsultationService service = mock(OnlineConsultationService.class);
        doThrow(new ApiException(400, "图片不能超过 2MB")).when(service).sendImageForPatient(eq(12L), eq(21L), any());
        MockMvc mvc = standalone(service);
        long maxBytes = new Contracts().consultationPhotoLimits().maxBytes();

        mvc.perform(multipart("/api/c/online-consultations/21/photos")
                        .file(new MockMultipartFile("file", "big.png", "image/png", new byte[(int) maxBytes + 1]))
                        .requestAttr("authSubject", 12L))
                .andExpect(status().isBadRequest());
        verify(service).sendImageForPatient(eq(12L), eq(21L), any());
    }

    private MockMvc standalone(OnlineConsultationService service) {
        return MockMvcBuilders.standaloneSetup(new OnlineConsultationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private OnlineConsultationViews.MessageView message(Long id, String senderType, String content) {
        return new OnlineConsultationViews.MessageView(id, senderType, "text", content, "2026-08-07T10:06:00+08:00");
    }

    private OnlineConsultationViews.ConsultationDetail detail(String status) {
        OnlineConsultationViews.ConsultationSummaryView summary =
                new OnlineConsultationViews.ConsultationSummaryView("咳嗽三天", "干咳无痰", "无");
        boolean cancelled = "CANCELLED".equals(status);
        return new OnlineConsultationViews.ConsultationDetail(
                21L,
                status,
                cancelled ? "已取消" : "等待医生接诊",
                cancelled ? null : "WAITING_DOCTOR",
                2L,
                "呼吸内科",
                summary,
                "仅供参考，不替代医生诊断",
                null,
                null,
                null,
                null,
                null,
                null,
                "2026-08-07T10:10:00+08:00",
                null,
                null,
                null,
                cancelled ? "2026-08-07T10:05:00+08:00" : null,
                "2026-08-07T10:00:00+08:00",
                cancelled ? "问诊已取消。可复用原病情摘要重新提交，无需重复预问诊。" : null);
    }

    private OnlineConsultationViews.MessageView imageMessage(Long id, String content) {
        return new OnlineConsultationViews.MessageView(id, "PATIENT", "image", content, "2026-08-07T10:06:00+08:00");
    }
}
