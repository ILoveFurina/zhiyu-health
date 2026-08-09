package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.controller.patient.appointment.AppointmentController;
import com.zhiyu.health.controller.patient.appointment.mapping.AppointmentCardMapper;
import com.zhiyu.health.service.appointment.AppointmentService;
import com.zhiyu.health.support.TestDisclaimers;
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
        AppointmentService.AppointmentView booked = appointment("待就诊");
        AppointmentService.AppointmentView cancelled = appointment("已取消");
        when(service.listForPatient(12L)).thenReturn(List.of(booked));
        when(service.cancel(12L, 21L)).thenReturn(cancelled);
        when(service.isPaymentPayable("UNPAID")).thenReturn(true);
        MockMvc mvc = standaloneSetup(new AppointmentController(service, TestDisclaimers.instance(), appointmentCards))
                .build();

        mvc.perform(get("/api/c/appointments").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doctor_name").value("周安宁"))
                .andExpect(jsonPath("$[0].status_code").value("BOOKED"))
                .andExpect(jsonPath("$[0].registration_fee").value(30.00))
                .andExpect(jsonPath("$[0].payment_status").value("UNPAID"))
                .andExpect(jsonPath("$[0].payment_status_label").value("待支付"))
                .andExpect(jsonPath("$[0].payment_payable").value(true))
                .andExpect(jsonPath("$[0].condition_summary").value("主诉胸闷两天"))
                .andExpect(jsonPath("$[0].hospital_name").value("智愈第一医院"))
                .andExpect(jsonPath("$[0].campus_name").value("主院区"))
                .andExpect(jsonPath("$[0].campus_address").value("郑州市金水区健康路 88 号"))
                .andExpect(jsonPath("$[0].summary_disclaimer").value("仅供参考，不替代医生诊断"));

        mvc.perform(post("/api/c/appointments/21/cancel").requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已取消"));
        verify(service).cancel(12L, 21L);
    }

    @Test
    void directlyCreatesAppointmentForAuthenticatedPatient() throws Exception {
        AppointmentService service = mock(AppointmentService.class);
        when(service.createDirect(12L, 9L)).thenReturn(appointment("待就诊"));
        MockMvc mvc = standaloneSetup(new AppointmentController(service, TestDisclaimers.instance(), appointmentCards))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/c/appointments")
                        .contentType("application/json")
                        .content("{\"schedule_id\":9}")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointment_id").value(21))
                .andExpect(jsonPath("$.schedule_id").value(9));
        verify(service).createDirect(12L, 9L);
    }

    @Test
    void directDuplicateReturnsExplicitConflict() throws Exception {
        AppointmentService service = mock(AppointmentService.class);
        when(service.createDirect(12L, 9L)).thenThrow(new ApiException(409, "请勿重复挂号"));
        MockMvc mvc = standaloneSetup(new AppointmentController(service, TestDisclaimers.instance(), appointmentCards))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(post("/api/c/appointments")
                        .contentType("application/json")
                        .content("{\"schedule_id\":9}")
                        .requestAttr("authSubject", "12"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请勿重复挂号"));
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
                "已取消".equals(status) ? "CANCELLED" : "BOOKED",
                status,
                new BigDecimal("30.00"),
                "UNPAID",
                "待支付",
                "主诉胸闷两天",
                "智愈第一医院",
                "主院区",
                "郑州市金水区健康路 88 号",
                "2026-07-28T10:00:00+08:00");
    }
}
