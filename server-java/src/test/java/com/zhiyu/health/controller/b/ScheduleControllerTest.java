package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.service.ScheduleService;
import com.zhiyu.health.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScheduleController.class)
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScheduleService scheduleService;

    @Test
    void adminCanCreateScheduleWithInitializedRemainingSlots() throws Exception {
        Schedule created = schedule(1L, 20, 20, true);
        when(scheduleService.createSchedule(any(Schedule.class))).thenReturn(created);

        mockMvc.perform(post("/api/b/schedules")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("""
                                {"doctor_id": 1, "schedule_date": "2026-07-29",
                                 "time_slot": "上午", "total_slots": 20}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctor_id").value(1))
                .andExpect(jsonPath("$.schedule_date").value("2026-07-29"))
                .andExpect(jsonPath("$.time_slot").value("上午"))
                .andExpect(jsonPath("$.total_slots").value(20))
                .andExpect(jsonPath("$.remaining_slots").value(20))
                .andExpect(jsonPath("$.is_active").value(true));
    }

    @Test
    void adminCanListAndReadSchedules() throws Exception {
        Schedule schedule = schedule(1L, 20, 12, true);
        when(scheduleService.listSchedules()).thenReturn(List.of(schedule));
        when(scheduleService.getSchedule(1L)).thenReturn(schedule);

        mockMvc.perform(get("/api/b/schedules")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].remaining_slots").value(12));
        mockMvc.perform(get("/api/b/schedules/1")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void adminCanUpdateSchedule() throws Exception {
        Schedule updated = schedule(1L, 24, 16, true);
        when(scheduleService.updateSchedule(any(Schedule.class))).thenReturn(updated);

        mockMvc.perform(put("/api/b/schedules/1")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("""
                                {"doctor_id": 1, "schedule_date": "2026-07-30",
                                 "time_slot": "下午", "total_slots": 24}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_slots").value(24))
                .andExpect(jsonPath("$.remaining_slots").value(16));
    }

    @Test
    void adminCanDisableAndDeleteWithoutRemovingSlotPool() throws Exception {
        Schedule disabled = schedule(1L, 20, 12, false);
        when(scheduleService.disableSchedule(1L)).thenReturn(disabled);

        mockMvc.perform(patch("/api/b/schedules/1/disable")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_active").value(false));
        mockMvc.perform(delete("/api/b/schedules/1")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());

        verify(scheduleService, org.mockito.Mockito.times(2)).disableSchedule(1L);
    }

    @Test
    void missingScheduleReturns404() throws Exception {
        // 404 判定已下沉到 service；此处模拟 service 抛出，验证 advice 出口形状
        when(scheduleService.getSchedule(99L)).thenThrow(new ApiException(404, "排班不存在"));

        mockMvc.perform(get("/api/b/schedules/99")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("排班不存在"));
    }

    @Test
    void scheduleInputRejectsUnsupportedTimeSlotAndNonPositiveSlots() throws Exception {
        mockMvc.perform(post("/api/b/schedules")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("""
                                {"doctor_id": 1, "schedule_date": "2026-07-29",
                                 "time_slot": "凌晨", "total_slots": 0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void doctorCannotManageSchedules() throws Exception {
        mockMvc.perform(get("/api/b/schedules")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    private Schedule schedule(Long id, int totalSlots, int remainingSlots, boolean active) {
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setDoctorId(1L);
        schedule.setScheduleDate(LocalDate.of(2026, 7, 29));
        schedule.setTimeSlot(TimeSlot.MORNING);
        schedule.setTotalSlots(totalSlots);
        schedule.setRemainingSlots(remainingSlots);
        schedule.setIsActive(active);
        return schedule;
    }
}
