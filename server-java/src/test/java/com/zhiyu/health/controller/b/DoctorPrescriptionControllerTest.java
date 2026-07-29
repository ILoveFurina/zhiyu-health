package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
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

@WebMvcTest(DoctorPrescriptionController.class)
class DoctorPrescriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrescriptionService service;

    @Test
    void doctorListsMedicationsAndCreatesPendingPrescription() throws Exception {
        when(service.listMedications(8L))
                .thenReturn(List.of(new PrescriptionService.MedicationView(1L, "阿莫西林胶囊", "阿莫西林", "0.25g*24粒", "饭后口服")));
        when(service.create(any()))
                .thenReturn(new PrescriptionService.PrescriptionView(
                        31L, 21L, "待审核", null, null, null, "小愈", "林知远", "2026-07-29", List.of()));

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
                                "notes":"饭后服用"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("待审核"));
        verify(service).listMedications(8L);
    }

    @Test
    void createRejectsEmptyItems() throws Exception {
        mockMvc.perform(post("/api/b/reception/appointments/21/prescriptions")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
