package com.zhiyu.health.controller.b;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.PrescriptionService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PrescriptionReviewController.class)
class PrescriptionReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrescriptionService service;

    @Test
    void adminListsAndApprovesPrescription() throws Exception {
        PrescriptionService.PrescriptionView approved = new PrescriptionService.PrescriptionView(
                31L,
                21L,
                null,
                "APPOINTMENT",
                "线下接诊",
                "已通过",
                null,
                "按医嘱服用。",
                "仅供参考，不替代医生诊断",
                "小愈",
                "林知远",
                "2026-07-29",
                "上呼吸道感染",
                "清淡饮食",
                List.of());
        when(service.listForReview("PENDING")).thenReturn(List.of(approved));
        when(service.review(1L, 31L, "APPROVE", null)).thenReturn(approved);

        mockMvc.perform(get("/api/b/prescriptions?status=PENDING")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/b/prescriptions/31/review")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已通过"));
        verify(service).review(1L, 31L, "APPROVE", null);
    }

    @Test
    void doctorCannotAccessPrescriptionReviewApi() throws Exception {
        mockMvc.perform(get("/api/b/prescriptions").with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/b/prescriptions/31/review")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
    }
}
