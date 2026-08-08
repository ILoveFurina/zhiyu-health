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
import com.zhiyu.health.controller.staff.organization.HospitalController;
import com.zhiyu.health.controller.staff.organization.mapping.HospitalInputMapperImpl;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.organization.Hospital;
import com.zhiyu.health.service.organization.HospitalAdminService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 医院 CRUD seam：admin 角色门、404/409 分支、精简后的名称+等级输入（票 49） */
@WebMvcTest(HospitalController.class)
@Import(HospitalInputMapperImpl.class)
class HospitalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalAdminService hospitalAdminService;

    private static final String VALID_BODY = """
            {"name": "郑州智愈综合医院", "level": "三级甲等"}
            """;

    private Hospital demoHospital() {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setName("郑州智愈综合医院");
        hospital.setLevel("三级甲等");
        return hospital;
    }

    @Test
    void listReturnsHospitals() throws Exception {
        when(hospitalAdminService.listAll()).thenReturn(List.of(demoHospital()));

        mockMvc.perform(get("/api/b/hospitals").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("郑州智愈综合医院"));
    }

    @Test
    void listRejectsDoctorRole() throws Exception {
        mockMvc.perform(get("/api/b/hospitals").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createReturns201() throws Exception {
        when(hospitalAdminService.create(any(Hospital.class))).thenReturn(demoHospital());

        mockMvc.perform(post("/api/b/hospitals")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("郑州智愈综合医院"));
    }

    @Test
    void createRejectsDoctorRole() throws Exception {
        mockMvc.perform(post("/api/b/hospitals")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/b/hospitals")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"name\": \"\", \"level\": \"三级甲等\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(hospitalAdminService.update(any(Hospital.class))).thenThrow(new ApiException(404, "医院不存在"));

        mockMvc.perform(put("/api/b/hospitals/99")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("医院不存在"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/b/hospitals/1").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        doThrow(new ApiException(404, "医院不存在")).when(hospitalAdminService).delete(99L);

        mockMvc.perform(delete("/api/b/hospitals/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("医院不存在"));
    }

    @Test
    void deleteReturns409WhenCampusesExist() throws Exception {
        doThrow(new ApiException(409, "医院下存在院区，无法删除"))
                .when(hospitalAdminService)
                .delete(1L);

        mockMvc.perform(delete("/api/b/hospitals/1").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("医院下存在院区，无法删除"));
    }
}
