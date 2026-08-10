package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.staff.chat.AgentCallLogController;
import com.zhiyu.health.controller.staff.organization.CampusController;
import com.zhiyu.health.controller.staff.organization.HospitalController;
import com.zhiyu.health.controller.staff.organization.mapping.CampusInputMapper;
import com.zhiyu.health.controller.staff.organization.mapping.HospitalInputMapper;
import com.zhiyu.health.controller.staff.pharmacy.CampusPharmacyController;
import com.zhiyu.health.controller.staff.pharmacy.PharmacyConfigController;
import com.zhiyu.health.controller.staff.pharmacy.PharmacyMedicationController;
import com.zhiyu.health.controller.staff.prescription.DrugOrderAdminController;
import com.zhiyu.health.controller.staff.prescription.MedicationController;
import com.zhiyu.health.controller.staff.prescription.PrescriptionReviewController;
import com.zhiyu.health.controller.staff.prescription.mapping.MedicationInputMapper;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.service.chat.AgentCallLogService;
import com.zhiyu.health.service.organization.CampusAdminService;
import com.zhiyu.health.service.organization.HospitalAdminService;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import com.zhiyu.health.service.pharmacy.PharmacyMedicationService;
import com.zhiyu.health.service.prescription.DrugOrderService;
import com.zhiyu.health.service.prescription.MedicationAdminService;
import com.zhiyu.health.service.prescription.PrescriptionService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 票 88 路由级角色矩阵负向边界（ADR-0035）：admin-only / admin-or-pharmacist / doctor-only
 * 三档在 HTTP 层强制执行，不依赖页面隐藏；患者 token 由 AuthFilter 在 scope 层 401。
 */
@WebMvcTest({
    HospitalController.class,
    CampusController.class,
    AgentCallLogController.class,
    PrescriptionReviewController.class,
    DrugOrderAdminController.class,
    CampusPharmacyController.class,
    PharmacyMedicationController.class,
    PharmacyConfigController.class,
    MedicationController.class
})
class StaffRoleAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalAdminService hospitalAdminService;

    @MockitoBean
    private HospitalInputMapper hospitalInputMapper;

    @MockitoBean
    private CampusAdminService campusAdminService;

    @MockitoBean
    private CampusInputMapper campusInputMapper;

    @MockitoBean
    private AgentCallLogService agentCallLogService;

    @MockitoBean
    private PrescriptionService prescriptionService;

    @MockitoBean
    private DrugOrderService drugOrderService;

    @MockitoBean
    private CampusPharmacyService campusPharmacyService;

    @MockitoBean
    private PharmacyMedicationService pharmacyMedicationService;

    @MockitoBean
    private MedicationAdminService medicationAdminService;

    @MockitoBean
    private MedicationInputMapper medicationInputMapper;

    @Test
    void pharmacistForbiddenFromAdminOnlyRoutes() throws Exception {
        // 药师越权系统日志：403，请求不进入业务层
        mockMvc.perform(get("/api/b/agent-call-logs")
                        .param("conversation_id", "1")
                        .with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void pharmacistReadOnlyOnOrgDirectory() throws Exception {
        // 组织目录（医院/院区）GET 只读对药师放行（药房库存页选药房依赖）；写操作仍 admin-only
        when(hospitalAdminService.listAll()).thenReturn(List.of());
        when(campusAdminService.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/b/hospitals").with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/b/campuses").with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/b/hospitals")
                        .contentType("application/json")
                        .content("{\"name\":\"测试医院\",\"level\":\"三甲\"}")
                        .with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
        mockMvc.perform(post("/api/b/campuses")
                        .contentType("application/json")
                        .content(
                                "{\"hospital_id\":1,\"name\":\"测试院区\",\"city_code\":\"440300\",\"city_name\":\"深圳\",\"address\":\"测试路1号\"}")
                        .with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST)))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorForbiddenFromReviewInventoryAndOrders() throws Exception {
        // 医生越权处方审核 / 院区药房库存 / 药品订单：403（权限边界与菜单隐藏无关）
        mockMvc.perform(get("/api/b/prescriptions").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员或药师可操作"));

        mockMvc.perform(get("/api/b/campus-pharmacies").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员或药师可操作"));

        mockMvc.perform(get("/api/b/drug-orders").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员或药师可操作"));

        // 医生越权标准药品目录（药师现场补药依赖，admin/pharmacist 共享）：403
        mockMvc.perform(get("/api/b/medications").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员或药师可操作"));
    }

    @Test
    void pharmacistAndAdminShareReviewInventoryAndOrders() throws Exception {
        when(prescriptionService.listForReview(null, null)).thenReturn(List.of());
        when(drugOrderService.listForAdmin(isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(new DrugOrderService.AdminOrderPage(List.of(), 0, 1, 20));
        when(campusPharmacyService.listAll()).thenReturn(List.of());
        when(campusPharmacyService.requireByCampusId(5L)).thenReturn(new CampusPharmacy());
        when(medicationAdminService.listAll()).thenReturn(List.of());

        for (String role : new String[] {StaffUser.ROLE_ADMIN, StaffUser.ROLE_PHARMACIST}) {
            mockMvc.perform(get("/api/b/prescriptions").with(StaffTokens.withRole(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/b/drug-orders").with(StaffTokens.withRole(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/b/campus-pharmacies").with(StaffTokens.withRole(role)))
                    .andExpect(status().isOk());
            // 按院区读药房：admin/pharmacist 均可
            mockMvc.perform(get("/api/b/campuses/5/pharmacy").with(StaffTokens.withRole(role)))
                    .andExpect(status().isOk());
            // 标准药品目录（补药/处方属性维护）：admin/pharmacist 均可
            mockMvc.perform(get("/api/b/medications").with(StaffTokens.withRole(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void patientTokenRejectedFromStaffRoutes() throws Exception {
        // C 端患者 token（scope=c_patient）碰 B 端：AuthFilter 在 scope 层 401，与角色无关
        mockMvc.perform(get("/api/b/hospitals").with(StaffTokens.withPatientSubject("12")))
                .andExpect(status().isUnauthorized());
    }
}
