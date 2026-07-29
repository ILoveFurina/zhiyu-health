package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.service.PatientCareService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PatientCareController.class)
class PatientCareControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientCareService service;

    @Test
    void patientReadsOnlyServiceFilteredApprovedPrescriptionsAndMessages() throws Exception {
        when(service.approvedPrescriptions(7L))
                .thenReturn(List.of(new PatientCareService.PatientPrescriptionView(
                        31L, "林知远", "心血管内科", "2026-07-29", "按医嘱服用。", "仅供参考，不替代医生诊断", List.of())));
        when(service.messages(7L))
                .thenReturn(List.of(new PatientCareService.MessageView(
                        41L,
                        "CONSULTATION_SUMMARY",
                        "就诊小结",
                        "本次诊断为上呼吸道感染。",
                        "仅供参考，不替代医生诊断",
                        "2026-07-29T10:00:00+08:00")));

        mockMvc.perform(get("/api/c/prescriptions").with(StaffTokens.withPatientSubject("7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].interpretation").value("按医嘱服用。"));
        mockMvc.perform(get("/api/c/messages").with(StaffTokens.withPatientSubject("7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("就诊小结"));
        verify(service).approvedPrescriptions(7L);
        verify(service).messages(7L);
    }
}
