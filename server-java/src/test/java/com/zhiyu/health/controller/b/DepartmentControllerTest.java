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
import com.zhiyu.health.controller.b.mapping.DepartmentInputMapperImpl;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.DepartmentAdminService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 科室 CRUD seam：楼层/位置字段（就诊指引卡数据源）与医院外键 404 */
@WebMvcTest(DepartmentController.class)
@Import(DepartmentInputMapperImpl.class)
class DepartmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepartmentAdminService departmentAdminService;

    private static final String VALID_BODY =
            """
            {"hospital_id": 1, "name": "心血管内科", "floor": "门诊楼 3 层", "location": "东区 301 室"}
            """;

    private Department demoDepartment() {
        Department department = new Department();
        department.setId(1L);
        department.setHospitalId(1L);
        department.setName("心血管内科");
        department.setFloor("门诊楼 3 层");
        department.setLocation("东区 301 室");
        return department;
    }

    @Test
    void listReturnsDepartmentsWithFloorAndLocation() throws Exception {
        when(departmentAdminService.listAll()).thenReturn(List.of(demoDepartment()));

        mockMvc.perform(get("/api/b/departments").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hospital_id").value(1))
                .andExpect(jsonPath("$[0].floor").value("门诊楼 3 层"))
                .andExpect(jsonPath("$[0].location").value("东区 301 室"));
    }

    @Test
    void createRejectsDoctorRole() throws Exception {
        mockMvc.perform(post("/api/b/departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createReturns201() throws Exception {
        when(departmentAdminService.create(any(Department.class))).thenReturn(demoDepartment());

        mockMvc.perform(post("/api/b/departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("心血管内科"));
    }

    @Test
    void createReturns404WhenHospitalMissing() throws Exception {
        when(departmentAdminService.create(any(Department.class))).thenThrow(new ApiException(404, "医院不存在"));

        mockMvc.perform(post("/api/b/departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("医院不存在"));
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(departmentAdminService.update(any(Department.class))).thenThrow(new ApiException(404, "科室或医院不存在"));

        mockMvc.perform(put("/api/b/departments/99")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("科室或医院不存在"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/b/departments/1").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        doThrow(new ApiException(404, "科室不存在")).when(departmentAdminService).delete(99L);

        mockMvc.perform(delete("/api/b/departments/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("科室不存在"));
    }
}
