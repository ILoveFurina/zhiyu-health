package com.zhiyu.health.controller.b;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.staff.pharmacy.PharmacyConfigController;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.service.pharmacy.CampusPharmacyService;
import com.zhiyu.health.support.StaffTokens;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 按院区读药房（票 88）：admin/pharmacist 均可读；配置写操作在 PUT /api/b/campus-pharmacies/{id}。 */
@WebMvcTest(PharmacyConfigController.class)
class PharmacyConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CampusPharmacyService campusPharmacyService;

    private CampusPharmacy pharmacy() {
        CampusPharmacy pharmacy = new CampusPharmacy();
        pharmacy.setId(71L);
        pharmacy.setCampusId(11L);
        pharmacy.setDisplayName("主院区药房");
        pharmacy.setDeliveryFee(new BigDecimal("5.00"));
        pharmacy.setEstimatedDeliveryMinutes(45);
        return pharmacy;
    }

    @Test
    void pharmacistReadsPharmacyByCampus() throws Exception {
        when(campusPharmacyService.requireByCampusId(11L)).thenReturn(pharmacy());

        for (String role : new String[] {StaffUser.ROLE_ADMIN, StaffUser.ROLE_PHARMACIST}) {
            mockMvc.perform(get("/api/b/campuses/11/pharmacy").with(StaffTokens.withRole(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.campus_id").value(11))
                    .andExpect(jsonPath("$.delivery_fee").value(5.00))
                    .andExpect(jsonPath("$.estimated_delivery_minutes").value(45));
        }
    }
}
