package com.zhiyu.health.controller.b.demo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.b.MedicationController;
import com.zhiyu.health.controller.b.mapping.MedicationInputMapperImpl;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.DemoDashboardService;
import com.zhiyu.health.service.DemoKnowledgeSourceService;
import com.zhiyu.health.service.DemoPharmacySyncService;
import com.zhiyu.health.service.DemoResetService;
import com.zhiyu.health.service.MedicationAdminService;
import com.zhiyu.health.support.StaffTokens;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Mock 药店库存同步（票 48）HTTP seam：使用真实 {@link DemoPharmacySyncService} 加载类路径 fixture，
 * 覆盖同步统计、快照时间流转，以及核心负向断言——同步动作执行后 medications 列表价格/库存无任何变化
 * （展示层不触碰业务库存，ADR-0026 修订例外）。
 */
@WebMvcTest(controllers = {DemoController.class, MedicationController.class})
@Import({DemoPharmacySyncService.class, MedicationInputMapperImpl.class})
class DemoPharmacySyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DemoResetService resetService;

    @MockitoBean
    private DemoDashboardService dashboardService;

    @MockitoBean
    private DemoKnowledgeSourceService knowledgeSourceService;

    @MockitoBean
    private MedicationAdminService medicationAdminService;

    private static Medication demoMedication() {
        Medication medication = new Medication();
        medication.setId(1L);
        medication.setName("阿莫西林胶囊");
        medication.setGenericName("阿莫西林");
        medication.setSpecification("0.25g*24粒");
        medication.setInstructions("口服；青霉素过敏者禁用");
        medication.setPrice(new BigDecimal("18.50"));
        medication.setStock(320);
        medication.setIsActive(true);
        return medication;
    }

    @Test
    void syncThenSnapshotReflectsSyncedTime() throws Exception {
        mockMvc.perform(get("/api/b/demo/pharmacy-stock").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last_synced_at").doesNotExist())
                .andExpect(jsonPath("$.pharmacies.length()").value(3));

        mockMvc.perform(post("/api/b/demo/pharmacy-stock/sync").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pharmacy_count").value(3))
                .andExpect(jsonPath("$.record_count").value(12))
                .andExpect(jsonPath("$.synced_at").exists());

        mockMvc.perform(get("/api/b/demo/pharmacy-stock").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last_synced_at").exists())
                .andExpect(jsonPath("$.pharmacies.length()").value(3));
    }

    @Test
    void syncDoesNotChangeMedicationList() throws Exception {
        when(medicationAdminService.listAll()).thenReturn(List.of(demoMedication()));

        MvcResult before = mockMvc.perform(get("/api/b/medications").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andReturn();
        String beforeBody = before.getResponse().getContentAsString();

        mockMvc.perform(post("/api/b/demo/pharmacy-stock/sync").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk());

        // 负向断言：同步后 medications 列表（含价格/库存）与同步前逐字节一致，且无写路径调用
        mockMvc.perform(get("/api/b/medications").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(beforeBody));
        verify(medicationAdminService, times(2)).listAll();
        verify(medicationAdminService, never()).update(any(Medication.class));
    }
}
