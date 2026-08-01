package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.service.AppointmentService;
import com.zhiyu.health.support.TestDisclaimers;
import com.zhiyu.health.controller.mapping.AppointmentCardMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;

class AppointmentControllerTest {

    private final AppointmentCardMapper appointmentCards = Mappers.getMapper(AppointmentCardMapper.class);

    @Test
    void listsCurrentPatientsAppointmentsAndCancelsOne() throws Exception {
        AppointmentService service = mock(AppointmentService.class);
        AppointmentService.AppointmentView booked = appointment("已约");
        AppointmentService.AppointmentView cancelled = appointment("已取消");
        when(service.listForPatient(12L)).thenReturn(List.of(booked));
        when(service.cancel(12L, 21L)).thenReturn(cancelled);
        MockMvc mvc = standaloneSetup(new AppointmentController(service, TestDisclaimers.instance(), appointmentCards))
                .build();

        mvc.perform(get("/api/c/appointments").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doctor_name").value("周安宁"))
                .andExpect(jsonPath("$[0].registration_fee").value(30.00))
                .andExpect(jsonPath("$[0].payment_status").value("UNPAID"))
                .andExpect(jsonPath("$[0].payment_status_label").value("待支付"))
                .andExpect(jsonPath("$[0].condition_summary").value("主诉胸闷两天"))
                .andExpect(jsonPath("$[0].summary_disclaimer").value("仅供参考，不替代医生诊断"));

        mvc.perform(post("/api/c/appointments/21/cancel").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已取消"));
        verify(service).cancel(12L, 21L);
    }

    private AppointmentService.AppointmentView appointment(String status) {
        return new AppointmentService.AppointmentView(
                21L,
                9L,
                2L,
                "周安宁",
                "心血管内科",
                "2026-07-29",
                "上午",
                1,
                status,
                new BigDecimal("30.00"),
                "UNPAID",
                "待支付",
                "主诉胸闷两天",
                "2026-07-28T10:00:00+08:00");
    }
}
