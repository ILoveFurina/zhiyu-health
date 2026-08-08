package com.zhiyu.health.controller.b;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.staff.consultation.OnlineConsultationController;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.service.consultation.OnlineConsultationService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 票 55 在线问诊 B 端 HTTP seam：科室池、原子接受、发起方式、医患消息与完成的装配与错误出口。 */
@WebMvcTest(OnlineConsultationController.class)
class OnlineConsultationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OnlineConsultationService consultations;

    @Test
    void poolReturnsDepartmentScopedItems() throws Exception {
        when(consultations.pool(8L)).thenReturn(List.of(poolItem()));
        mockMvc.perform(get("/api/b/reception/online-consultations/pool")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations[0].id").value(21))
                .andExpect(jsonPath("$.consultations[0].status").value("WAITING_DOCTOR"))
                .andExpect(jsonPath("$.consultations[0].status_label").value("等待医生接诊"))
                .andExpect(jsonPath("$.consultations[0].standard_department_id").value(2))
                .andExpect(
                        jsonPath("$.consultations[0].standard_department_name").value("呼吸内科"))
                .andExpect(
                        jsonPath("$.consultations[0].summary.chief_complaint").value("咳嗽三天"))
                .andExpect(jsonPath("$.consultations[0].summary_disclaimer").value("仅供参考，不替代医生诊断"))
                .andExpect(jsonPath("$.consultations[0].patient.nickname").value("小愈"))
                .andExpect(jsonPath("$.consultations[0].health_profile.display_name")
                        .value("小愈本人"))
                .andExpect(jsonPath("$.consultations[0].health_profile.allergies[0]")
                        .value("青霉素"))
                .andExpect(jsonPath("$.consultations[0].expires_at").value("2026-08-07T10:10:00+08:00"));
        verify(consultations).pool(8L);
    }

    @Test
    void poolRejectsUnmappableDoctorDepartment() throws Exception {
        when(consultations.pool(8L)).thenThrow(new ApiException(409, "医生科室未映射标准科室，暂不可接诊在线问诊"));
        mockMvc.perform(get("/api/b/reception/online-consultations/pool")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isConflict());
    }

    @Test
    void adminRoleIsRejectedByServiceLevelDoctorGuard() throws Exception {
        // 接诊台命名空间对 AdminInterceptor 放行，doctor 角色收口在业务层（与 ReceptionService 同一模式）
        when(consultations.pool(1L)).thenThrow(new ApiException(403, "仅医生可操作"));
        mockMvc.perform(get("/api/b/reception/online-consultations/pool")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void mineFiltersByStatus() throws Exception {
        when(consultations.mine(8L, "IN_PROGRESS")).thenReturn(List.of(poolItem()));
        mockMvc.perform(get("/api/b/reception/online-consultations/mine?status=IN_PROGRESS")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations[0].id").value(21));
        verify(consultations).mine(8L, "IN_PROGRESS");
    }

    @Test
    void mineRejectsUnsupportedStatusFilter() throws Exception {
        when(consultations.mine(8L, "WAITING_DOCTOR"))
                .thenThrow(new ApiException(400, "status 仅支持 IN_PROGRESS/COMPLETED"));
        mockMvc.perform(get("/api/b/reception/online-consultations/mine?status=WAITING_DOCTOR")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailReturnsFullConsultationWithoutMutating() throws Exception {
        when(consultations.detailForDoctor(8L, 21L)).thenReturn(detail("WAITING_DOCTOR"));
        mockMvc.perform(get("/api/b/reception/online-consultations/21")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultation.id").value(21))
                .andExpect(jsonPath("$.consultation.patient.nickname").value("小愈"))
                .andExpect(
                        jsonPath("$.consultation.health_profile.relationship").value("本人"))
                .andExpect(jsonPath("$.consultation.summary.present_illness").value("干咳无痰"));
        verify(consultations).detailForDoctor(8L, 21L);
    }

    @Test
    void crossDepartmentDoctorGets404OnDetailAndAccept() throws Exception {
        when(consultations.detailForDoctor(8L, 21L)).thenThrow(new ApiException(404, "问诊单不存在"));
        when(consultations.accept(8L, 21L)).thenThrow(new ApiException(404, "问诊单不存在"));

        mockMvc.perform(get("/api/b/reception/online-consultations/21")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/b/reception/online-consultations/21/accept")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptBindsDoctorAndReturnsInProgress() throws Exception {
        when(consultations.accept(8L, 21L)).thenReturn(detail("IN_PROGRESS"));
        mockMvc.perform(post("/api/b/reception/online-consultations/21/accept")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultation.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.consultation.accepted_at").value("2026-08-07T10:03:00+08:00"));
        verify(consultations).accept(8L, 21L);
    }

    @Test
    void acceptConflictYields409() throws Exception {
        when(consultations.accept(8L, 21L)).thenThrow(new ApiException(409, "该问诊单已被其他医生接受"));
        mockMvc.perform(post("/api/b/reception/online-consultations/21/accept")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("该问诊单已被其他医生接受"));
    }

    @Test
    void startMethodValidatesContractMethods() throws Exception {
        mockMvc.perform(post("/api/b/reception/online-consultations/21/start-method")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"method\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startMethodConflictYields409() throws Exception {
        when(consultations.startMethod(8L, 21L, "TEXT")).thenThrow(new ApiException(409, "接诊方式已发起，不可更换"));
        mockMvc.perform(post("/api/b/reception/online-consultations/21/start-method")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"method\":\"TEXT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("接诊方式已发起，不可更换"));
    }

    @Test
    void startMethodReturnsUpdatedConsultation() throws Exception {
        when(consultations.startMethod(8L, 21L, "VIDEO")).thenReturn(detail("IN_PROGRESS"));
        mockMvc.perform(post("/api/b/reception/online-consultations/21/start-method")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"method\":\"VIDEO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultation.status").value("IN_PROGRESS"));
        verify(consultations).startMethod(8L, 21L, "VIDEO");
    }

    @Test
    void messagesFlowBothDirections() throws Exception {
        when(consultations.listMessagesForDoctor(8L, 21L, 0L))
                .thenReturn(List.of(new OnlineConsultationService.MessageView(
                        41L, "PATIENT", "text", "医生你好", "2026-08-07T10:04:00+08:00")));
        when(consultations.sendMessageForDoctor(8L, 21L, "你好，请补充体温"))
                .thenReturn(new OnlineConsultationService.MessageView(
                        42L, "DOCTOR", "text", "你好，请补充体温", "2026-08-07T10:05:00+08:00"));

        mockMvc.perform(get("/api/b/reception/online-consultations/21/messages")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].sender_type").value("PATIENT"));
        mockMvc.perform(post("/api/b/reception/online-consultations/21/messages")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"content\":\"你好，请补充体温\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.sender_type").value("DOCTOR"));
        verify(consultations).listMessagesForDoctor(8L, 21L, 0L);
        verify(consultations).sendMessageForDoctor(8L, 21L, "你好，请补充体温");
    }

    @Test
    void completeRequiresDiagnosisAndAdvice() throws Exception {
        mockMvc.perform(post("/api/b/reception/online-consultations/21/complete")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"diagnosis\":\" \",\"advice\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeReturnsCompletedConsultationAndIsIdempotent() throws Exception {
        when(consultations.complete(8L, 21L, "急性上呼吸道感染", "清淡饮食，按需复诊")).thenReturn(detail("COMPLETED"));
        // 重复完成走同一接口返回当前单（幂等语义由 service 保证，HTTP 形状一致）
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/b/reception/online-consultations/21/complete")
                            .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                            .contentType("application/json")
                            .content("{\"diagnosis\":\"急性上呼吸道感染\",\"advice\":\"清淡饮食，按需复诊\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.consultation.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.consultation.diagnosis").value("急性上呼吸道感染"))
                    .andExpect(jsonPath("$.consultation.advice").value("清淡饮食，按需复诊"));
        }
    }

    @Test
    void completeOnWaitingConsultationYields409() throws Exception {
        doThrow(new ApiException(409, "问诊不在进行中")).when(consultations).complete(8L, 21L, "急性上呼吸道感染", "清淡饮食");
        mockMvc.perform(post("/api/b/reception/online-consultations/21/complete")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"diagnosis\":\"急性上呼吸道感染\",\"advice\":\"清淡饮食\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void prescriptionLookupReturnsCardOrNull() throws Exception {
        // 票 60 接诊抽屉：有处方返回状态/标签/驳回原因，无处方 prescription 为 null（两态 200）
        when(consultations.prescriptionForConsultation(8L, 21L))
                .thenReturn(
                        new OnlineConsultationService.ConsultationPrescriptionView(31L, "REJECTED", "已驳回", "用法用量需调整"));
        mockMvc.perform(get("/api/b/reception/online-consultations/21/prescription")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prescription.id").value(31))
                .andExpect(jsonPath("$.prescription.status").value("REJECTED"))
                .andExpect(jsonPath("$.prescription.status_label").value("已驳回"))
                .andExpect(jsonPath("$.prescription.review_reason").value("用法用量需调整"));
        when(consultations.prescriptionForConsultation(8L, 22L)).thenReturn(null);
        mockMvc.perform(get("/api/b/reception/online-consultations/22/prescription")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prescription").value(nullValue()));
    }

    private OnlineConsultationService.DoctorListItem poolItem() {
        return new OnlineConsultationService.DoctorListItem(
                21L,
                "WAITING_DOCTOR",
                "等待医生接诊",
                2L,
                "呼吸内科",
                summary(),
                "仅供参考，不替代医生诊断",
                new OnlineConsultationService.PatientRef("小愈"),
                profile(),
                null,
                null,
                null,
                null,
                "2026-08-07T10:00:00+08:00",
                "2026-08-07T10:10:00+08:00");
    }

    private OnlineConsultationService.DoctorConsultationDetail detail(String status) {
        boolean inProgress = "IN_PROGRESS".equals(status);
        boolean completed = "COMPLETED".equals(status);
        return new OnlineConsultationService.DoctorConsultationDetail(
                21L,
                status,
                completed ? "问诊已完成" : (inProgress ? "医生问诊中" : "等待医生接诊"),
                2L,
                "呼吸内科",
                summary(),
                "仅供参考，不替代医生诊断",
                new OnlineConsultationService.PatientRef("小愈"),
                profile(),
                inProgress || completed ? "VIDEO" : null,
                inProgress || completed ? "视频问诊" : null,
                inProgress || completed ? "2026-08-07T10:04:00+08:00" : null,
                completed ? "急性上呼吸道感染" : null,
                completed ? "清淡饮食，按需复诊" : null,
                inProgress || completed ? "2026-08-07T10:03:00+08:00" : null,
                completed ? "2026-08-07T10:06:00+08:00" : null,
                null,
                "2026-08-07T10:00:00+08:00",
                "2026-08-07T10:10:00+08:00");
    }

    private OnlineConsultationService.ConsultationSummaryView summary() {
        return new OnlineConsultationService.ConsultationSummaryView("咳嗽三天", "干咳无痰", "无");
    }

    private OnlineConsultationService.ProfileRef profile() {
        return new OnlineConsultationService.ProfileRef("小愈本人", "女", "1990-01-01", "本人", List.of("青霉素"));
    }
}
