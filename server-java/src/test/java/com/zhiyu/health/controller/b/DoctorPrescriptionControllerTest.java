package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.staff.prescription.DoctorPrescriptionController;
import com.zhiyu.health.controller.staff.prescription.mapping.PrescriptionInputMapper;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.prescription.PrescriptionService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DoctorPrescriptionController.class)
class DoctorPrescriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrescriptionService service;

    @MockitoBean
    private PrescriptionInputMapper inputMapper;

    @Test
    void doctorListsMedicationsAndCreatesPendingPrescription() throws Exception {
        when(service.listMedications(8L, null))
                .thenReturn(List.of(new PrescriptionService.MedicationView(1L, "阿莫西林胶囊", "阿莫西林", "0.25g*24粒", "饭后口服")));
        when(service.create(any()))
                .thenReturn(new PrescriptionService.PrescriptionView(
                        31L,
                        21L,
                        null,
                        "APPOINTMENT",
                        "线下接诊",
                        "待审核",
                        null,
                        null,
                        null,
                        "小愈",
                        "林知远",
                        "2026-07-29",
                        null,
                        null,
                        List.of()));
        when(inputMapper.toCommand(anyLong(), anyLong(), any()))
                .thenReturn(new PrescriptionService.CreateCommand(
                        8L,
                        21L,
                        "足疗程服用",
                        List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, "饭后服用"))));

        mockMvc.perform(get("/api/b/reception/medications").with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("阿莫西林胶囊"));

        mockMvc.perform(
                        post("/api/b/reception/appointments/21/prescriptions")
                                .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                                {"notes":"足疗程服用","items":[{"medication_id":1,
                                "dosage":"0.5g","frequency":"每日3次","duration":"5天",
                                "quantity":2,"notes":"饭后服用"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("待审核"));
        verify(service).listMedications(8L, null);
    }

    @Test
    void createRejectsNonPositiveQuantity() throws Exception {
        // 票 88：配药数量由医生填写且必须为正整数，请求体层直接 400
        mockMvc.perform(
                        post("/api/b/reception/appointments/21/prescriptions")
                                .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                        {"items":[{"medication_id":1,
                        "dosage":"0.5g","frequency":"每日3次","duration":"5天","quantity":0}]}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsEmptyItems() throws Exception {
        mockMvc.perform(post("/api/b/reception/appointments/21/prescriptions")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkSafetyReturnsDeterministicResult() throws Exception {
        PrescriptionService.CheckSafetyCommand command =
                new PrescriptionService.CheckSafetyCommand(8L, 21L, List.of(1L));
        when(inputMapper.toSafetyCommand(anyLong(), anyLong(), any())).thenReturn(command);
        ContraindicationResult result = new ContraindicationResult(
                "BLOCKED",
                "contraindication_warning",
                true,
                List.of("过敏史“青霉素”与药品 1 的成分/禁忌项匹配"),
                "检测到用药禁忌，已阻止本次药品推荐。请咨询医生或药师后再用药。",
                "请咨询医生或药师，并主动告知完整过敏史和正在使用的药品。");
        when(service.checkSafety(command)).thenReturn(result);
        when(inputMapper.toSafetyResponse(result))
                .thenReturn(new DoctorPrescriptionController.SafetyCheckResponse(
                        result.decision(),
                        result.messageType(),
                        result.blocked(),
                        result.reasons(),
                        result.message(),
                        result.advice()));

        mockMvc.perform(post("/api/b/reception/appointments/21/contraindication-check")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"medication_ids\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("BLOCKED"))
                .andExpect(jsonPath("$.message_type").value("contraindication_warning"))
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.reasons[0]").value("过敏史“青霉素”与药品 1 的成分/禁忌项匹配"));
    }

    @Test
    void checkSafetyRejectsEmptyMedicationIds() throws Exception {
        mockMvc.perform(post("/api/b/reception/appointments/21/contraindication-check")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"medication_ids\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkSafetyRejectsForeignAppointment() throws Exception {
        when(inputMapper.toSafetyCommand(anyLong(), anyLong(), any()))
                .thenReturn(new PrescriptionService.CheckSafetyCommand(8L, 21L, List.of(1L)));
        when(service.checkSafety(any())).thenThrow(new ApiException(404, "挂号单不存在"));

        mockMvc.perform(post("/api/b/reception/appointments/21/contraindication-check")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"medication_ids\":[1]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("挂号单不存在"));
    }

    @Test
    void checkSafetyRejectsNonDoctor() throws Exception {
        when(inputMapper.toSafetyCommand(anyLong(), anyLong(), any()))
                .thenReturn(new PrescriptionService.CheckSafetyCommand(1L, 21L, List.of(1L)));
        when(service.checkSafety(any())).thenThrow(new ApiException(403, "仅医生可操作"));

        mockMvc.perform(post("/api/b/reception/appointments/21/contraindication-check")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"medication_ids\":[1]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅医生可操作"));
    }

    @Test
    void createRejectsBlockedSubmissionBypassingFrontend() throws Exception {
        when(inputMapper.toCommand(anyLong(), anyLong(), any()))
                .thenReturn(new PrescriptionService.CreateCommand(
                        8L, 21L, null, List.of(new PrescriptionService.CreateItem(1L, "0.5g", "每日3次", "5天", 2, null))));
        when(service.create(any()))
                .thenThrow(new ApiException(409, "检测到用药禁忌，已阻止本次处方提交。请调整用药方案或咨询药师：过敏史“青霉素”与药品 1 的成分/禁忌项匹配"));

        mockMvc.perform(
                        post("/api/b/reception/appointments/21/prescriptions")
                                .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                        {"items":[{"medication_id":1,
                        "dosage":"0.5g","frequency":"每日3次","duration":"5天","quantity":2}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("已阻止本次处方提交")));
    }
}
