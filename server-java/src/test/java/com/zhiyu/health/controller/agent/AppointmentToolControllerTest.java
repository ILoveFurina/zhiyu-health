package com.zhiyu.health.controller.agent;

import com.zhiyu.health.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AppointmentToolControllerTest {

    @Test
    void createToolValidatesAndDelegatesToAppointmentService() throws Exception {
        AppointmentService service = mock(AppointmentService.class);
        when(service.createWithSummary(12L, 7L, 9L, "主诉胸闷两天"))
                .thenReturn(appointmentWithoutSummary());
        MockMvc mvc = standaloneSetup(new AppointmentToolController(service)).build();

        mvc.perform(post("/api/agent/appointments")
                        .contentType("application/json")
                        .content("""
                                {"patient_id":12,"conversation_id":7,"schedule_id":9,
                                 "condition_summary":"主诉胸闷两天"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointment_id").value(21))
                .andExpect(jsonPath("$.summary_sent").value(false));
        verify(service).createWithSummary(12L, 7L, 9L, "主诉胸闷两天");
    }

    @Test
    void getToolReturnsOnlyTrustedRuntimePatientsAppointments() throws Exception {
        AppointmentService service = mock(AppointmentService.class);
        when(service.listForPatient(12L)).thenReturn(List.of(appointment()));
        MockMvc mvc = standaloneSetup(new AppointmentToolController(service)).build();

        mvc.perform(get("/api/agent/appointments").param("patient_id", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointments[0].appointment_id").value(21));
    }

    @Test
    void savesSummaryAfterAppointmentSuccess() throws Exception {
        AppointmentService service = mock(AppointmentService.class);
        when(service.saveConditionSummary(12L, 7L, 21L, "主诉胸闷两天"))
                .thenReturn(appointment());
        MockMvc mvc = standaloneSetup(new AppointmentToolController(service)).build();

        mvc.perform(post("/api/agent/appointments/21/summary")
                        .contentType("application/json")
                        .content("""
                                {"patient_id":12,"conversation_id":7,
                                 "condition_summary":"主诉胸闷两天"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary_sent").value(true))
                .andExpect(jsonPath("$.notice").value("病情摘要已发送给医生"));
    }

    private AppointmentService.AppointmentView appointment() {
        return new AppointmentService.AppointmentView(
                21L, 9L, 2L, "周安宁", "心血管内科", "2026-07-29", "上午",
                1, "已约", "主诉胸闷两天。仅供参考，不替代医生诊断",
                "2026-07-28T10:00:00+08:00");
    }

    private AppointmentService.AppointmentView appointmentWithoutSummary() {
        return new AppointmentService.AppointmentView(
                21L, 9L, 2L, "周安宁", "心血管内科", "2026-07-29", "上午",
                1, "已约", null, "2026-07-28T10:00:00+08:00");
    }
}
