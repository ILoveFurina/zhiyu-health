package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.staff.prescription.MedicationController;
import com.zhiyu.health.controller.staff.prescription.mapping.MedicationInputMapperImpl;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.service.prescription.MedicationAdminService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 标准药品目录 seam（票 88）：admin/pharmacist 角色门、仅编辑处方属性（无价格/库存/上下架
 * 语义——已收敛到院区药房药品）、404 分支、snake_case 字段。
 */
@WebMvcTest(MedicationController.class)
@Import(MedicationInputMapperImpl.class)
class MedicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MedicationAdminService medicationAdminService;

    private static final String VALID_BODY = """
            {"is_prescription": true}
            """;

    private Medication demoMedication() {
        Medication medication = new Medication();
        medication.setId(1L);
        medication.setName("阿莫西林胶囊");
        medication.setGenericName("阿莫西林");
        medication.setSpecification("0.25g*24粒");
        medication.setInstructions("口服；青霉素过敏者禁用");
        medication.setIsPrescription(true);
        return medication;
    }

    @Test
    void listReturnsCatalogMedications() throws Exception {
        when(medicationAdminService.listAll()).thenReturn(List.of(demoMedication()));

        mockMvc.perform(get("/api/b/medications").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("阿莫西林胶囊"))
                .andExpect(jsonPath("$[0].specification").value("0.25g*24粒"))
                .andExpect(jsonPath("$[0].is_prescription").value(true));
    }

    @Test
    void listRejectsDoctorRole() throws Exception {
        mockMvc.perform(get("/api/b/medications").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员或药师可操作"));
    }

    @Test
    void updateReturnsUpdatedMedication() throws Exception {
        when(medicationAdminService.update(any(Medication.class))).thenReturn(demoMedication());

        mockMvc.perform(put("/api/b/medications/1")
                        .with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_prescription").value(true))
                .andExpect(jsonPath("$.name").value("阿莫西林胶囊"));
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(medicationAdminService.update(any(Medication.class))).thenThrow(new ApiException(404, "药品不存在"));

        mockMvc.perform(put("/api/b/medications/99")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("药品不存在"));
    }

    @Test
    void updateRejectsMissingIsPrescription() throws Exception {
        mockMvc.perform(put("/api/b/medications/1")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRejectsDoctorRole() throws Exception {
        mockMvc.perform(put("/api/b/medications/1")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员或药师可操作"));
    }
}
