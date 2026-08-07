package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.b.mapping.PrescriptionInputMapper;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.PrescriptionService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 在线问诊开方端点（票 55）：HTTP 外部行为；身份派生与状态守卫由 service 层测试覆盖。 */
@WebMvcTest(OnlineConsultationPrescriptionController.class)
class OnlineConsultationPrescriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrescriptionService service;

    @MockitoBean
    private PrescriptionInputMapper inputMapper;

    @Test
    void createReturnsPendingPrescriptionWithOnlineSource() throws Exception {
        when(inputMapper.toOnlineCommand(anyLong(), anyLong(), any()))
                .thenReturn(new PrescriptionService.CreateOnlineCommand(
                        8L, 41L, "足疗程服用", List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", null))));
        when(service.createFromOnlineConsultation(any()))
                .thenReturn(new PrescriptionService.PrescriptionView(
                        51L,
                        null,
                        41L,
                        "ONLINE_CONSULTATION",
                        "在线问诊",
                        "待审核",
                        "足疗程服用",
                        null,
                        null,
                        "小愈",
                        "林知远",
                        "2026-08-01",
                        null,
                        null,
                        List.of()));

        mockMvc.perform(
                        post("/api/b/reception/online-consultations/41/prescriptions")
                                .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                                {"notes":"足疗程服用","items":[{"medication_id":1,
                                "dosage":"0.5g","frequency":"每日3次","duration":"5天"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_type").value("ONLINE_CONSULTATION"))
                .andExpect(jsonPath("$.source_type_label").value("在线问诊"))
                .andExpect(jsonPath("$.online_consultation_id").value(41))
                .andExpect(jsonPath("$.status").value("待审核"));
    }

    @Test
    void createRejectsEmptyItems() throws Exception {
        mockMvc.perform(post("/api/b/reception/online-consultations/41/prescriptions")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsBlockedDuplicateForeignAndNotInProgress() throws Exception {
        when(inputMapper.toOnlineCommand(anyLong(), anyLong(), any()))
                .thenReturn(new PrescriptionService.CreateOnlineCommand(
                        8L, 41L, null, List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", null))));
        String body =
                "{\"items\":[{\"medication_id\":1,\"dosage\":\"0.5g\",\"frequency\":\"每日3次\",\"duration\":\"5天\"}]}";

        // 提交侧禁忌拦截（绕过前端禁用按钮直连 API 也必须 fail closed）
        // 用 doThrow 而非 when(...).thenThrow：重复打桩已 thenThrow 的 mock 会在 when() 内真实抛出
        org.mockito.Mockito.doThrow(new ApiException(409, "检测到用药禁忌，已阻止本次处方提交。请调整用药方案或咨询药师"))
                .when(service)
                .createFromOnlineConsultation(any());
        mockMvc.perform(post("/api/b/reception/online-consultations/41/prescriptions")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("已阻止本次处方提交")));

        // 同一问诊重复开方：明确冲突
        org.mockito.Mockito.doThrow(new ApiException(409, "该问诊已开具电子处方"))
                .when(service)
                .createFromOnlineConsultation(any());
        mockMvc.perform(post("/api/b/reception/online-consultations/41/prescriptions")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("该问诊已开具电子处方"));

        // 非绑定医生：404（与票 54 既有守卫一致，不区分不存在与越权）
        org.mockito.Mockito.doThrow(new ApiException(404, "问诊单不存在"))
                .when(service)
                .createFromOnlineConsultation(any());
        mockMvc.perform(post("/api/b/reception/online-consultations/41/prescriptions")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("问诊单不存在"));

        // 非进行中：409
        org.mockito.Mockito.doThrow(new ApiException(409, "问诊不在进行中"))
                .when(service)
                .createFromOnlineConsultation(any());
        mockMvc.perform(post("/api/b/reception/online-consultations/41/prescriptions")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("问诊不在进行中"));
    }

    @Test
    void checkSafetyReturnsDeterministicResult() throws Exception {
        when(inputMapper.toOnlineSafetyCommand(anyLong(), anyLong(), any()))
                .thenReturn(new PrescriptionService.CheckSafetyOnlineCommand(8L, 41L, List.of(1L)));
        ContraindicationResult result =
                new ContraindicationResult("SAFE", "contraindication_result", false, List.of(), "未发现已知禁忌", null);
        when(service.checkSafetyFromOnlineConsultation(any())).thenReturn(result);
        when(inputMapper.toSafetyResponse(result))
                .thenReturn(new DoctorPrescriptionController.SafetyCheckResponse(
                        result.decision(),
                        result.messageType(),
                        result.blocked(),
                        result.reasons(),
                        result.message(),
                        result.advice()));

        mockMvc.perform(post("/api/b/reception/online-consultations/41/contraindication-check")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"medication_ids\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("SAFE"))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void checkSafetyRejectsNonDoctor() throws Exception {
        when(inputMapper.toOnlineSafetyCommand(anyLong(), anyLong(), any()))
                .thenReturn(new PrescriptionService.CheckSafetyOnlineCommand(1L, 41L, List.of(1L)));
        when(service.checkSafetyFromOnlineConsultation(any())).thenThrow(new ApiException(403, "仅医生可操作"));

        mockMvc.perform(post("/api/b/reception/online-consultations/41/contraindication-check")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"medication_ids\":[1]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅医生可操作"));
    }
}
