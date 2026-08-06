package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.b.mapping.CampusInputMapperImpl;
import com.zhiyu.health.entity.HospitalCampus;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.CampusAdminService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 院区 CRUD seam（票 49）：结构化城市字段、坐标范围校验、404/409 分支 */
@WebMvcTest(CampusController.class)
@Import(CampusInputMapperImpl.class)
class CampusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CampusAdminService campusAdminService;

    private static final String VALID_BODY =
            """
            {"hospital_id": 1, "name": "主院区", "city_code": "410100", "city_name": "郑州市",
             "address": "郑州市金水区健康路 88 号", "longitude": 113.6458, "latitude": 34.7572,
             "floor": "门诊楼 1 层导诊台", "materials": "身份证或医保卡", "precautions": "建议提前 30 分钟到达"}
            """;

    private HospitalCampus demoCampus() {
        HospitalCampus campus = new HospitalCampus();
        campus.setId(11L);
        campus.setHospitalId(1L);
        campus.setName("主院区");
        campus.setCityCode("410100");
        campus.setCityName("郑州市");
        campus.setAddress("郑州市金水区健康路 88 号");
        campus.setLongitude(113.6458);
        campus.setLatitude(34.7572);
        return campus;
    }

    @Test
    void listReturnsCampuses() throws Exception {
        when(campusAdminService.listAll()).thenReturn(List.of(demoCampus()));

        mockMvc.perform(get("/api/b/campuses").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[0].hospital_id").value(1))
                .andExpect(jsonPath("$[0].city_code").value("410100"))
                .andExpect(jsonPath("$[0].longitude").value(113.6458));
    }

    @Test
    void createRejectsDoctorRole() throws Exception {
        mockMvc.perform(post("/api/b/campuses")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createReturns201() throws Exception {
        when(campusAdminService.create(any(HospitalCampus.class))).thenReturn(demoCampus());

        mockMvc.perform(post("/api/b/campuses")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("主院区"))
                .andExpect(jsonPath("$.city_name").value("郑州市"));
    }

    @Test
    void createRejectsMissingCityCode() throws Exception {
        mockMvc.perform(post("/api/b/campuses")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"hospital_id\": 1, \"name\": \"主院区\", \"city_name\": \"郑州市\","
                                + " \"address\": \"郑州市金水区健康路 88 号\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsOutOfRangeLongitude() throws Exception {
        mockMvc.perform(post("/api/b/campuses")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"hospital_id\": 1, \"name\": \"主院区\", \"city_code\": \"410100\","
                                + " \"city_name\": \"郑州市\", \"address\": \"郑州市金水区健康路 88 号\","
                                + " \"longitude\": 200.0, \"latitude\": 34.0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns404WhenHospitalMissing() throws Exception {
        when(campusAdminService.create(any(HospitalCampus.class))).thenThrow(new ApiException(404, "医院不存在"));

        mockMvc.perform(post("/api/b/campuses")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("医院不存在"));
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(campusAdminService.update(any(HospitalCampus.class))).thenThrow(new ApiException(404, "院区或医院不存在"));

        mockMvc.perform(put("/api/b/campuses/99")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("院区或医院不存在"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/b/campuses/11").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        doThrow(new ApiException(404, "院区不存在")).when(campusAdminService).delete(99L);

        mockMvc.perform(delete("/api/b/campuses/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("院区不存在"));
    }

    @Test
    void deleteReturns409WhenDepartmentsExist() throws Exception {
        doThrow(new ApiException(409, "院区下存在科室，无法删除")).when(campusAdminService).delete(11L);

        mockMvc.perform(delete("/api/b/campuses/11").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("院区下存在科室，无法删除"));
    }
}
