package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.staff.pharmacy.CampusPharmacyController;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import com.zhiyu.health.service.pharmacy.PharmacyMedicationService;
import com.zhiyu.health.support.StaffTokens;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 院区药房端点（票 88）：admin/pharmacist 主链路冒烟；越权矩阵见 StaffRoleAuthorizationTest。 */
@WebMvcTest(CampusPharmacyController.class)
class CampusPharmacyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CampusPharmacyService campusPharmacyService;

    @MockitoBean
    private PharmacyMedicationService pharmacyMedicationService;

    private CampusPharmacy pharmacy() {
        CampusPharmacy pharmacy = new CampusPharmacy();
        pharmacy.setId(71L);
        pharmacy.setCampusId(11L);
        pharmacy.setDisplayName("主院区药房");
        pharmacy.setDeliveryFee(new BigDecimal("5.00"));
        pharmacy.setEstimatedDeliveryMinutes(45);
        return pharmacy;
    }

    private PharmacyMedicationService.PharmacyMedicationView view() {
        return new PharmacyMedicationService.PharmacyMedicationView(
                501L, 71L, 12L, "阿莫西林胶囊", "阿莫西林", "0.25g*24粒", true, new BigDecimal("12.50"), 30, true);
    }

    @Test
    void pharmacistListsPharmaciesAndMedications() throws Exception {
        when(campusPharmacyService.listAll()).thenReturn(List.of(pharmacy()));
        when(pharmacyMedicationService.listMedications(71L, null)).thenReturn(List.of(view()));

        // 药师与 admin 共享药房库存入口（票 88 角色矩阵）
        mockMvc.perform(get("/api/b/campus-pharmacies").with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(71))
                .andExpect(jsonPath("$[0].campus_id").value(11))
                .andExpect(jsonPath("$[0].display_name").value("主院区药房"));

        mockMvc.perform(get("/api/b/campus-pharmacies/71/medications")
                        .with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("阿莫西林胶囊"))
                .andExpect(jsonPath("$[0].price").value(12.50))
                .andExpect(jsonPath("$[0].stock").value(30))
                .andExpect(jsonPath("$[0].is_on_sale").value(true));
    }

    @Test
    void listMedicationsPassesKeyword() throws Exception {
        when(pharmacyMedicationService.listMedications(71L, "阿莫")).thenReturn(List.of(view()));

        mockMvc.perform(get("/api/b/campus-pharmacies/71/medications")
                        .param("keyword", "阿莫")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("阿莫西林胶囊"));
    }

    @Test
    void pharmacistUpdatesConfig() throws Exception {
        // 药房基础配置对 admin/pharmacist 开放（票 88 定稿：药师负责药房运营）
        when(campusPharmacyService.updateConfig(eq(71L), any(), any(), any())).thenReturn(pharmacy());

        mockMvc.perform(
                        put("/api/b/campus-pharmacies/71")
                                .with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST))
                                .contentType("application/json")
                                .content(
                                        "{\"display_name\":\"主院区中心药房\",\"delivery_fee\":6.50,\"estimated_delivery_minutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campus_id").value(11));
    }

    @Test
    void configRejectsNegativeDeliveryFee() throws Exception {
        mockMvc.perform(
                        put("/api/b/campus-pharmacies/71")
                                .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                                .contentType("application/json")
                                .content(
                                        "{\"display_name\":\"主院区药房\",\"delivery_fee\":-0.01,\"estimated_delivery_minutes\":45}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void configRejectsNonPositiveEstimatedMinutes() throws Exception {
        mockMvc.perform(put("/api/b/campus-pharmacies/71")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"display_name\":\"主院区药房\",\"delivery_fee\":5.00,\"estimated_delivery_minutes\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminAddsMedication() throws Exception {
        when(pharmacyMedicationService.add(eq(71L), any())).thenReturn(view());

        mockMvc.perform(post("/api/b/campus-pharmacies/71/medications")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"medication_id\":12,\"price\":12.50,\"stock\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_on_sale").value(true));
    }

    @Test
    void addRejectsNegativePrice() throws Exception {
        mockMvc.perform(post("/api/b/campus-pharmacies/71/medications")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"medication_id\":12,\"price\":-1.00,\"stock\":30}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addRejectsMissingMedicationId() throws Exception {
        mockMvc.perform(post("/api/b/campus-pharmacies/71/medications")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"price\":12.50,\"stock\":30}"))
                .andExpect(status().isBadRequest());
    }
}
