package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.staff.scheduling.ScheduleRequestDoctorController;
import com.zhiyu.health.controller.staff.scheduling.ScheduleReviewController;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.scheduling.ScheduleRequest;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.service.scheduling.ScheduleRequestService;
import com.zhiyu.health.support.StaffTokens;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 排班申请审核 MockMvc 冒烟：医生提交 + 管理员审核主链路，角色门禁与 409 幂等。 */
@WebMvcTest({ScheduleRequestDoctorController.class, ScheduleReviewController.class})
class ScheduleRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScheduleRequestService service;

    @Test
    void doctorCanSubmitScheduleRequests() throws Exception {
        when(service.submit(anyLong(), anyLong(), any())).thenReturn(List.of(request(1L, "PENDING")));

        mockMvc.perform(post("/api/b/reception/schedule-requests")
                        .with(StaffTokens.withSubject("10", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(
                                """
                                {"doctor_id": 5,
                                 "items": [{"schedule_date": "%s", "time_slot": "上午", "total_slots": 10}]}
                                """
                                        .formatted(LocalDate.now())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void doctorCanListOwnScheduleRequests() throws Exception {
        when(service.listMine(anyLong())).thenReturn(List.of(request(1L, "APPROVED")));

        mockMvc.perform(get("/api/b/reception/schedule-requests/mine")
                        .with(StaffTokens.withSubject("10", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
    }

    @Test
    void adminCanListPendingScheduleRequests() throws Exception {
        // listForReview 参数可能为 null（@RequestParam required=false），用 nullable 匹配
        when(service.listForReview(org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(List.of(request(1L, "PENDING")));

        mockMvc.perform(get("/api/b/schedule-requests").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void adminCanApproveScheduleRequest() throws Exception {
        ScheduleRequest approved = request(1L, "APPROVED");
        approved.setScheduleId(100L);
        when(service.review(anyLong(), eq(1L), eq("APPROVE"), any())).thenReturn(approved);

        mockMvc.perform(
                        post("/api/b/schedule-requests/1/review")
                                .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                                .contentType("application/json")
                                .content(
                                        """
                                {"decision": "APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.schedule_id").value(100));
    }

    @Test
    void adminCanRejectScheduleRequestWithReason() throws Exception {
        ScheduleRequest rejected = request(1L, "REJECTED");
        rejected.setReviewReason("号源数过多，请调整后重新提交");
        when(service.review(anyLong(), eq(1L), eq("REJECT"), any())).thenReturn(rejected);

        mockMvc.perform(
                        post("/api/b/schedule-requests/1/review")
                                .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                                .contentType("application/json")
                                .content(
                                        """
                                {"decision": "REJECT", "reason": "号源数过多，请调整后重新提交"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void doctorCannotAccessAdminReviewEndpoint() throws Exception {
        mockMvc.perform(get("/api/b/schedule-requests").with(StaffTokens.withSubject("10", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCanViewOwnScheduleTable() throws Exception {
        when(service.listMySchedule(anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/api/b/reception/schedule-table")
                        .with(StaffTokens.withSubject("10", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk());
    }

    @Test
    void doctorCanSubmitChangeRequest() throws Exception {
        when(service.submitChange(
                        anyLong(), eq(50L), eq("disable"), org.mockito.ArgumentMatchers.nullable(Integer.class)))
                .thenReturn(request(1L, "PENDING"));

        mockMvc.perform(
                        post("/api/b/reception/schedules/50/change-request")
                                .with(StaffTokens.withSubject("10", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                                {"action": "disable"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void doctorCanSubmitEnableChangeRequest() throws Exception {
        when(service.submitChange(
                        anyLong(), eq(50L), eq("enable"), org.mockito.ArgumentMatchers.nullable(Integer.class)))
                .thenReturn(request(1L, "PENDING"));

        mockMvc.perform(
                        post("/api/b/reception/schedules/50/change-request")
                                .with(StaffTokens.withSubject("10", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                                {"action": "enable"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitRejectsEmptyItems() throws Exception {
        mockMvc.perform(
                        post("/api/b/reception/schedule-requests")
                                .with(StaffTokens.withSubject("10", StaffUser.ROLE_DOCTOR))
                                .contentType("application/json")
                                .content(
                                        """
                                {"doctor_id": 5, "items": []}
                                """))
                .andExpect(status().isBadRequest());
        verify(service, org.mockito.Mockito.never()).submit(anyLong(), anyLong(), any());
    }

    private ScheduleRequest request(long id, String status) {
        ScheduleRequest req = new ScheduleRequest();
        req.setId(id);
        req.setDoctorId(5L);
        req.setScheduleDate(LocalDate.now().plusDays(1));
        req.setTimeSlot(TimeSlot.MORNING);
        req.setTotalSlots(10);
        req.setAction("CREATE");
        req.setStatus(status);
        req.setSubmittedBy(10L);
        return req;
    }
}
