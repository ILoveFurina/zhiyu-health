package com.zhiyu.health.controller.b;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.ReceptionService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReceptionController.class)
class ReceptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReceptionService receptionService;

    @Test
    void doctorListsOnlyDashboardResolvedForAuthenticatedStaff() throws Exception {
        ReceptionService.ReceptionDashboard dashboard = new ReceptionService.ReceptionDashboard(
                "2026-07-28",
                List.of(new ReceptionService.ScheduleView(3L, "上午", 10, 4, true)),
                List.of(appointment("已约", null)));
        when(receptionService.today(8L)).thenReturn(dashboard);

        mockMvc.perform(get("/api/b/reception").with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules[0].time_slot").value("上午"))
                .andExpect(jsonPath("$.appointments[0].patient_nickname").value("小愈"))
                .andExpect(jsonPath("$.appointments[0].summary_disclaimer").value("仅供参考，不替代医生诊断"));
        verify(receptionService).today(8L);
    }

    @Test
    void doctorSeesPrescriptionStatusWhenPrescribed() throws Exception {
        // 接诊后药方待审核：view 携带 prescription_status，前端据此显示"待审核"而非"接诊"
        ReceptionService.ReceptionDashboard dashboard = new ReceptionService.ReceptionDashboard(
                "2026-07-28",
                List.of(new ReceptionService.ScheduleView(3L, "上午", 10, 4, true)),
                List.of(appointment("已接诊", "PENDING")));
        when(receptionService.today(8L)).thenReturn(dashboard);

        mockMvc.perform(get("/api/b/reception").with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointments[0].status").value("已接诊"))
                .andExpect(jsonPath("$.appointments[0].prescription_status").value("PENDING"));
    }

    @Test
    void doctorReadsAndCompletesAppointment() throws Exception {
        ReceptionService.AppointmentDetail detail = new ReceptionService.AppointmentDetail(
                appointment("已接诊", null), "上呼吸道感染", "清淡饮食，按需复诊", "2026-07-28T10:00:00+08:00");
        when(receptionService.detail(8L, 21L)).thenReturn(detail);
        when(receptionService.complete(8L, 21L, "上呼吸道感染", "清淡饮食，按需复诊")).thenReturn(detail);

        mockMvc.perform(get("/api/b/reception/appointments/21")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointment.condition_summary").value("咳嗽两天"));

        mockMvc.perform(
                        post("/api/b/reception/appointments/21/complete")
                                .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                                {"diagnosis":"上呼吸道感染","advice":"清淡饮食，按需复诊"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointment.status").value("已接诊"))
                .andExpect(jsonPath("$.diagnosis").value("上呼吸道感染"));
    }

    @Test
    void completeRejectsMissingDiagnosisOrAdvice() throws Exception {
        mockMvc.perform(post("/api/b/reception/appointments/21/complete")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{\"diagnosis\":\"  \",\"advice\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void doctorCallsAppointment() throws Exception {
        ReceptionService.AppointmentView appointment = new ReceptionService.AppointmentView(
                21L, 3L, "小愈", 2, "IN_PROGRESS", "就诊中", null, "2026-07-28", "上午", "咳嗽两天", "仅供参考，不替代医生诊断");
        ReceptionService.AppointmentDetail detail =
                new ReceptionService.AppointmentDetail(appointment, null, null, null);
        when(receptionService.call(8L, 21L)).thenReturn(detail);

        mockMvc.perform(post("/api/b/reception/appointments/21/call")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointment.status_code").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.appointment.status").value("就诊中"));
        verify(receptionService).call(8L, 21L);
    }

    private ReceptionService.AppointmentView appointment(String status, String prescriptionStatus) {
        return new ReceptionService.AppointmentView(
                21L,
                3L,
                "小愈",
                2,
                "已接诊".equals(status) ? "VISITED" : "BOOKED",
                status,
                prescriptionStatus,
                "2026-07-28",
                "上午",
                "咳嗽两天",
                "仅供参考，不替代医生诊断");
    }
}
