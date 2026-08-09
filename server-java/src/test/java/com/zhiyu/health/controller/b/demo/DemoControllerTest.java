package com.zhiyu.health.controller.b.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.staff.demo.DemoController;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.service.demo.DemoDashboardService;
import com.zhiyu.health.service.demo.DemoDashboardService.AgentActivity;
import com.zhiyu.health.service.demo.DemoDashboardService.DashboardView;
import com.zhiyu.health.service.demo.DemoDashboardService.DepartmentShare;
import com.zhiyu.health.service.demo.DemoDashboardService.SlotUsage;
import com.zhiyu.health.service.demo.DemoKnowledgeSourceService;
import com.zhiyu.health.service.demo.DemoPharmacySyncService;
import com.zhiyu.health.service.demo.DemoPharmacySyncService.PharmacyStock;
import com.zhiyu.health.service.demo.DemoPharmacySyncService.PharmacyStockItem;
import com.zhiyu.health.service.demo.DemoPharmacySyncService.PharmacyStockView;
import com.zhiyu.health.service.demo.DemoPharmacySyncService.SyncResult;
import com.zhiyu.health.service.demo.DemoResetService;
import com.zhiyu.health.service.demo.DemoResetService.ResetResult;
import com.zhiyu.health.support.StaffTokens;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 演示武器包入口（票 25）：仅 admin 可用，三重保护与中途失败经 service 抛 ApiException 出口。
 *
 * MockMvc 覆盖：未授权 401 / doctor 403 / 重置 env 未开启 403 / 确认短语不匹配 400 /
 * 重置进行中 409 / 中途失败 503 含步骤清单 / 知识源非法值 400 / 知识源默认 none / 看板聚合 /
 * Mock 药店库存同步与快照形状（票 48，真实 service 行为见 DemoPharmacySyncControllerTest）。
 */
@WebMvcTest(DemoController.class)
class DemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DemoResetService resetService;

    @MockitoBean
    private DemoDashboardService dashboardService;

    @MockitoBean
    private DemoKnowledgeSourceService knowledgeSourceService;

    @MockitoBean
    private DemoPharmacySyncService pharmacySyncService;

    @Test
    void noTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/b/demo/reset")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/b/demo/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/b/demo/knowledge-source")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/b/demo/pharmacy-stock/sync")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/b/demo/pharmacy-stock")).andExpect(status().isUnauthorized());
    }

    @Test
    void doctorTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/b/demo/reset")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/b/demo/dashboard").with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/b/demo/knowledge-source").with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/b/demo/pharmacy-stock/sync")
                        .with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/b/demo/pharmacy-stock").with(StaffTokens.withSubject("8", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetDisabledByDefaultReturnsForbidden() throws Exception {
        // env 开关未开启：service 抛 403 ApiException
        when(resetService.reset("DEMO_RESET_CONFIRM")).thenThrow(new ApiException(403, "演示重置未开启"));
        mockMvc.perform(post("/api/b/demo/reset")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"confirm\":\"DEMO_RESET_CONFIRM\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("演示重置未开启"));
    }

    @Test
    void resetWrongConfirmPhraseReturnsBadRequest() throws Exception {
        when(resetService.reset("wrong-phrase")).thenThrow(new ApiException(400, "确认短语不匹配"));
        mockMvc.perform(post("/api/b/demo/reset")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"confirm\":\"wrong-phrase\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("确认短语不匹配"));
    }

    @Test
    void resetAlreadyRunningReturnsConflict() throws Exception {
        when(resetService.reset("DEMO_RESET_CONFIRM")).thenThrow(new ApiException(409, "重置进行中"));
        mockMvc.perform(post("/api/b/demo/reset")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"confirm\":\"DEMO_RESET_CONFIRM\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void resetMidFailureReturnsServiceUnavailableWithProgress() throws Exception {
        // 中途失败保持冻结、返回步骤清单；service 返回 success=false 的 ResetResult
        ResetResult failed = new ResetResult(
                false,
                List.of("freeze", "clear_redis"),
                "truncate_tables",
                List.of("truncate_tables", "reseed", "rebuild_redis", "unfreeze", "assert"),
                true,
                Map.of("error", "TRUNCATE 失败"));
        when(resetService.reset("DEMO_RESET_CONFIRM")).thenReturn(failed);
        mockMvc.perform(post("/api/b/demo/reset")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"confirm\":\"DEMO_RESET_CONFIRM\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.failed_step").value("truncate_tables"))
                .andExpect(jsonPath("$.frozen_after").value(true))
                .andExpect(jsonPath("$.pending_steps[0]").value("truncate_tables"));
    }

    @Test
    void resetSuccessReturnsOkWithResult() throws Exception {
        ResetResult ok = new ResetResult(
                true,
                List.of("freeze", "clear_redis", "truncate_tables", "reseed", "rebuild_redis", "unfreeze", "assert"),
                null,
                List.of(),
                false,
                Map.of());
        when(resetService.reset("DEMO_RESET_CONFIRM")).thenReturn(ok);
        mockMvc.perform(post("/api/b/demo/reset")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"confirm\":\"DEMO_RESET_CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.frozen_after").value(false));
    }

    @Test
    void adminDashboardReturnsAggregated() throws Exception {
        when(dashboardService.dashboard())
                .thenReturn(new DashboardView(
                        12,
                        List.of(new DepartmentShare("心血管内科", 5), new DepartmentShare("皮肤科", 3)),
                        new SlotUsage(0.25),
                        new AgentActivity(8, 20)));
        mockMvc.perform(get("/api/b/demo/dashboard").with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today_appointments").value(12))
                .andExpect(jsonPath("$.department_distribution[0].department").value("心血管内科"))
                .andExpect(jsonPath("$.slot_usage.rate").value(0.25))
                .andExpect(jsonPath("$.agent_activity.chat_rounds").value(8))
                .andExpect(jsonPath("$.agent_activity.tool_calls").value(20));
    }

    @Test
    void getKnowledgeSourceDefaultsNone() throws Exception {
        when(knowledgeSourceService.current()).thenReturn("none");
        mockMvc.perform(get("/api/b/demo/knowledge-source").with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledge_source").value("none"));
    }

    @Test
    void putKnowledgeSourceRejectsInvalidValue() throws Exception {
        // service 校验非法值抛 400
        org.mockito.Mockito.doThrow(new ApiException(400, "不支持的知识源值"))
                .when(knowledgeSourceService)
                .update("invalid");
        mockMvc.perform(put("/api/b/demo/knowledge-source")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"knowledge_source\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("不支持的知识源值"));
    }

    @Test
    void putKnowledgeSourceAcceptsValidValue() throws Exception {
        // service.update 成功后 current 返回写入值；update 不抛异常即通过
        org.mockito.Mockito.doNothing().when(knowledgeSourceService).update("graph");
        when(knowledgeSourceService.current()).thenReturn("graph");
        mockMvc.perform(put("/api/b/demo/knowledge-source")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"knowledge_source\":\"graph\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledge_source").value("graph"));
    }

    @Test
    void syncPharmacyStockReturnsStats() throws Exception {
        when(pharmacySyncService.sync())
                .thenReturn(new SyncResult(OffsetDateTime.parse("2026-08-06T10:15:30+08:00"), 3, 12));
        mockMvc.perform(post("/api/b/demo/pharmacy-stock/sync")
                        .with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.synced_at").value("2026-08-06T10:15:30+08:00"))
                .andExpect(jsonPath("$.pharmacy_count").value(3))
                .andExpect(jsonPath("$.record_count").value(12));
    }

    @Test
    void pharmacyStockReturnsSnapshot() throws Exception {
        when(pharmacySyncService.snapshot())
                .thenReturn(new PharmacyStockView(
                        null,
                        List.of(new PharmacyStock(
                                "澜庭大药房", "城东区·梧桐路 12 号", List.of(new PharmacyStockItem("阿莫西林胶囊", "0.25g*24粒", 86))))));
        mockMvc.perform(get("/api/b/demo/pharmacy-stock").with(StaffTokens.withSubject("1", StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last_synced_at").doesNotExist())
                .andExpect(jsonPath("$.pharmacies[0].name").value("澜庭大药房"))
                .andExpect(jsonPath("$.pharmacies[0].region").value("城东区·梧桐路 12 号"))
                .andExpect(jsonPath("$.pharmacies[0].items[0].medication_name").value("阿莫西林胶囊"))
                .andExpect(jsonPath("$.pharmacies[0].items[0].stock").value(86));
    }
}
