package com.zhiyu.health.controller.b;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.staff.pharmacy.PharmacyMedicationController;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.service.pharmacy.PharmacyMedicationService;
import com.zhiyu.health.support.StaffTokens;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 药房药品在售关系维护端点（票 88）：按 id 定位，admin/pharmacist 可操作。 */
@WebMvcTest(PharmacyMedicationController.class)
class PharmacyMedicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PharmacyMedicationService pharmacyMedicationService;

    private PharmacyMedicationService.PharmacyMedicationView view() {
        return new PharmacyMedicationService.PharmacyMedicationView(
                501L, 71L, 12L, "阿莫西林胶囊", "阿莫西林", "0.25g*24粒", true, new BigDecimal("9.90"), 5, false);
    }

    @Test
    void pharmacistUpdatesAndRemovesMedication() throws Exception {
        when(pharmacyMedicationService.update(ArgumentMatchers.eq(501L), ArgumentMatchers.any()))
                .thenReturn(view());

        mockMvc.perform(put("/api/b/pharmacy-medications/501")
                        .with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST))
                        .contentType("application/json")
                        .content("{\"price\":9.90,\"stock\":5,\"is_on_sale\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(9.90))
                .andExpect(jsonPath("$.is_on_sale").value(false));

        mockMvc.perform(delete("/api/b/pharmacy-medications/501").with(StaffTokens.withRole(StaffUser.ROLE_PHARMACIST)))
                .andExpect(status().isNoContent());
    }
}
