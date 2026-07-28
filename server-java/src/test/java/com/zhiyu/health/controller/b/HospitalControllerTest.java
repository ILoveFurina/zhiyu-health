package com.zhiyu.health.controller.b;

import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.OrganizationService;
import com.zhiyu.health.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 医院 CRUD seam：admin 角色门、404 分支、snake_case 字段 */
@WebMvcTest(HospitalController.class)
class HospitalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    private static final String VALID_BODY = """
            {"name": "智愈市人民医院", "level": "三级甲等", "address": "智愈市安康路 88 号",
             "longitude": 121.4737, "latitude": 31.2304}
            """;

    private Hospital demoHospital() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setName("智愈市人民医院");
        hospital.setLevel("三级甲等");
        hospital.setAddress("智愈市安康路 88 号");
        hospital.setLongitude(121.4737);
        hospital.setLatitude(31.2304);
        return hospital;
    }

    @Test
    void listReturnsHospitals() throws Exception {
        when(organizationService.listHospitals()).thenReturn(List.of(demoHospital()));

        mockMvc.perform(get("/api/b/hospitals").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].longitude").value(121.4737));
    }

    @Test
    void listRejectsDoctorRole() throws Exception {
        mockMvc.perform(get("/api/b/hospitals").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createReturns201() throws Exception {
        when(organizationService.createHospital(any(Hospital.class))).thenReturn(demoHospital());

        mockMvc.perform(post("/api/b/hospitals").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("智愈市人民医院"));
    }

    @Test
    void createRejectsDoctorRole() throws Exception {
        mockMvc.perform(post("/api/b/hospitals").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createRejectsOutOfRangeLongitude() throws Exception {
        mockMvc.perform(post("/api/b/hospitals").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"name\": \"x\", \"level\": \"y\", \"address\": \"z\", "
                                + "\"longitude\": 200.0, \"latitude\": 31.0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(organizationService.updateHospital(any(Hospital.class))).thenReturn(null);

        mockMvc.perform(put("/api/b/hospitals/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("医院不存在"));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(organizationService.deleteHospital(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/b/hospitals/1").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        when(organizationService.deleteHospital(99L)).thenReturn(false);

        mockMvc.perform(delete("/api/b/hospitals/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("医院不存在"));
    }
}
