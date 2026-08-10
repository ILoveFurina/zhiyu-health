package com.zhiyu.health.controller.c;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.patient.pharmacy.PharmacyOtcCatalogController;
import com.zhiyu.health.service.pharmacy.PharmacyOtcCatalogService;
import com.zhiyu.health.support.StaffTokens;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** C 端药房 OTC 目录端点冒烟（票 95）：薄入口装配与患者令牌保护；业务行为见 PharmacyOtcCatalogServiceTest。 */
@WebMvcTest(PharmacyOtcCatalogController.class)
class PharmacyOtcCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PharmacyOtcCatalogService service;

    @Test
    void catalogReturnsGroupedShape() throws Exception {
        PharmacyOtcCatalogService.OtcCatalogView view =
                new PharmacyOtcCatalogService.OtcCatalogView(List.of(new PharmacyOtcCatalogService.PharmacyGroupView(
                        71L,
                        "主院区大药房",
                        "云澜医院",
                        "主院区",
                        "澜山市城东区梧桐路1号",
                        new BigDecimal("5.00"),
                        45,
                        320.5,
                        List.of(
                                new PharmacyOtcCatalogService.ItemView(
                                        2L, "布洛芬缓释胶囊", "布洛芬", "0.3g*20粒", new BigDecimal("12.50"), 30),
                                new PharmacyOtcCatalogService.ItemView(
                                        3L, "氯雷他定片", "氯雷他定", "10mg*6片", new BigDecimal("18.00"), 0)))));
        when(service.catalog(120.15, 30.27)).thenReturn(view);

        mockMvc.perform(get("/api/c/pharmacy-otc-catalog")
                        .param("lng", "120.15")
                        .param("lat", "30.27")
                        .with(StaffTokens.withPatientSubject("7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pharmacies[0].pharmacy_id").value(71))
                .andExpect(jsonPath("$.pharmacies[0].pharmacy_name").value("主院区大药房"))
                .andExpect(jsonPath("$.pharmacies[0].hospital_name").value("云澜医院"))
                .andExpect(jsonPath("$.pharmacies[0].campus_name").value("主院区"))
                .andExpect(jsonPath("$.pharmacies[0].delivery_fee").value(5.00))
                .andExpect(jsonPath("$.pharmacies[0].estimated_minutes").value(45))
                .andExpect(jsonPath("$.pharmacies[0].distance_meters").value(320.5))
                .andExpect(jsonPath("$.pharmacies[0].items[0].medication_id").value(2))
                .andExpect(jsonPath("$.pharmacies[0].items[0].name").value("布洛芬缓释胶囊"))
                .andExpect(jsonPath("$.pharmacies[0].items[0].generic_name").value("布洛芬"))
                .andExpect(jsonPath("$.pharmacies[0].items[0].price").value(12.50))
                .andExpect(jsonPath("$.pharmacies[0].items[1].stock").value(0));
        verify(service).catalog(120.15, 30.27);
    }

    @Test
    void catalogWithoutCoordsOmitsDistance() throws Exception {
        PharmacyOtcCatalogService.OtcCatalogView view =
                new PharmacyOtcCatalogService.OtcCatalogView(List.of(new PharmacyOtcCatalogService.PharmacyGroupView(
                        71L, "主院区大药房", "云澜医院", "主院区", "澜山市城东区梧桐路1号", new BigDecimal("5.00"), 45, null, List.of())));
        when(service.catalog(null, null)).thenReturn(view);

        mockMvc.perform(get("/api/c/pharmacy-otc-catalog").with(StaffTokens.withPatientSubject("7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pharmacies[0].pharmacy_id").value(71))
                .andExpect(jsonPath("$.pharmacies[0].distance_meters").doesNotExist());
    }

    @Test
    void catalogRequiresPatientToken() throws Exception {
        mockMvc.perform(get("/api/c/pharmacy-otc-catalog")).andExpect(status().isUnauthorized());
    }
}
