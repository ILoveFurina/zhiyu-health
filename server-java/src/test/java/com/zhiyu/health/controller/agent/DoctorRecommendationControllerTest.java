package com.zhiyu.health.controller.agent;

import com.zhiyu.health.config.AgentCallbackAuthFilter;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.service.DoctorRecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agent 业务工具回调 seam：只暴露当前仍有号源的医生与排班。 */
@WebMvcTest(DoctorRecommendationController.class)
class DoctorRecommendationControllerTest {

    /** 与 src/test/resources/application.properties 的回调密钥一致 */
    private static final String CALLBACK_SECRET = "test-only-agent-callback-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DoctorRecommendationService recommendationService;

    @Test
    void recommendsAvailableDoctorsByDepartment() throws Exception {
        when(recommendationService.recommendDoctors("心血管内科")).thenReturn(List.of(
                new DoctorRecommendationService.DoctorRecommendation(
                        2L, "周安宁", "副主任医师", "胸痛评估、心力衰竭",
                        "https://example.com/demo/zhou.jpg", 5)));

        mockMvc.perform(get("/api/agent/doctors/recommend")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, CALLBACK_SECRET)
                        .param("department_name", "心血管内科"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctors.length()").value(1))
                .andExpect(jsonPath("$.doctors[0].doctor_id").value(2))
                .andExpect(jsonPath("$.doctors[0].name").value("周安宁"))
                .andExpect(jsonPath("$.doctors[0].title").value("副主任医师"))
                .andExpect(jsonPath("$.doctors[0].specialty").value("胸痛评估、心力衰竭"))
                .andExpect(jsonPath("$.doctors[0].photo_url")
                        .value("https://example.com/demo/zhou.jpg"))
                .andExpect(jsonPath("$.doctors[0].remaining_slots").value(5));
    }

    @Test
    void returnsAvailableSlotsForDoctor() throws Exception {
        when(recommendationService.getDoctorSlots(2L)).thenReturn(List.of(
                new DoctorRecommendationService.DoctorSlot(
                        9L, LocalDate.of(2026, 7, 29), TimeSlot.MORNING, 3)));

        mockMvc.perform(get("/api/agent/doctors/2/slots")
                        .header(AgentCallbackAuthFilter.HEADER_NAME, CALLBACK_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctor_id").value(2))
                .andExpect(jsonPath("$.slots.length()").value(1))
                .andExpect(jsonPath("$.slots[0].schedule_id").value(9))
                .andExpect(jsonPath("$.slots[0].schedule_date").value("2026-07-29"))
                .andExpect(jsonPath("$.slots[0].time_slot").value("上午"))
                .andExpect(jsonPath("$.slots[0].remaining_slots").value(3));
    }
}
