package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.service.MedCheckinService;
import com.zhiyu.health.service.MedCheckinView;
import com.zhiyu.health.support.StaffTokens;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MedCheckinController.class)
class MedCheckinControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MedCheckinService service;

    @Test
    void patientListsPendingReminders() throws Exception {
        when(service.pendingReminders(7L))
                .thenReturn(List.of(new MedCheckinView(
                        201L, 31L, "阿莫西林胶囊", "0.5g", "每日3次", LocalDate.now(), "待打卡", null, "仅供参考，不替代医生诊断", null)));

        mockMvc.perform(get("/api/c/med-checkins").with(StaffTokens.withPatientSubject("7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].medication_name").value("阿莫西林胶囊"))
                .andExpect(jsonPath("$[0].status").value("待打卡"));
        verify(service).pendingReminders(7L);
    }

    @Test
    void patientChecksInMedication() throws Exception {
        when(service.check(7L, 201L))
                .thenReturn(new MedCheckinView(
                        201L,
                        31L,
                        "阿莫西林胶囊",
                        "0.5g",
                        "每日3次",
                        LocalDate.now(),
                        "已服用",
                        "2026-08-03T10:00:00+08:00",
                        "仅供参考，不替代医生诊断",
                        1));

        mockMvc.perform(post("/api/c/med-checkins/201/check").with(StaffTokens.withPatientSubject("7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已服用"))
                .andExpect(jsonPath("$.streak").value(1));
        verify(service).check(7L, 201L);
    }

    @Test
    void repeatCheckInIsIdempotentAndReturnsCheckedState() throws Exception {
        // 重复点击：service 返回已打卡状态（幂等不报错），controller 透传 200 + 已服用。
        when(service.check(7L, 201L))
                .thenReturn(new MedCheckinView(
                        201L,
                        31L,
                        "阿莫西林胶囊",
                        "0.5g",
                        "每日3次",
                        LocalDate.now(),
                        "已服用",
                        "2026-08-03T10:00:00+08:00",
                        "仅供参考，不替代医生诊断",
                        1));

        mockMvc.perform(post("/api/c/med-checkins/201/check").with(StaffTokens.withPatientSubject("7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已服用"));
    }

    @Test
    void checkingForeignProfileRecordReturns404() throws Exception {
        // 越权档案：记录不属于当前 patient -> service 抛 404，不泄露存在性。
        doThrow(new ApiException(404, "打卡记录不存在")).when(service).check(7L, 999L);

        mockMvc.perform(post("/api/c/med-checkins/999/check").with(StaffTokens.withPatientSubject("7")))
                .andExpect(status().isNotFound());
        verify(service).check(7L, 999L);
    }
}
