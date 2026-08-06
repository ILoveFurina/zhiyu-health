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

/** 科室 CRUD seam（票 49）：院区/分类/标准科室三外键输入、跨医院 400 与删除 409 */
@WebMvcTest(DepartmentController.class)
@Import(DepartmentInputMapperImpl.class)
class DepartmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepartmentAdminService departmentAdminService;

    private static final String VALID_BODY =
            """
            {"campus_id": 11, "category_id": 11, "standard_department_id": 1,
             "name": "心血管内科", "floor": "门诊楼 3 层", "location": "东区 301 室"}
            """;

    private Department demoDepartment() {
        Department department = new Department();
        department.setId(1L);
        department.setCampusId(11L);
        department.setCategoryId(11L);
        department.setStandardDepartmentId(1L);
        department.setName("心血管内科");
        department.setFloor("门诊楼 3 层");
        department.setLocation("东区 301 室");
        return department;
    }

    @Test
    void listReturnsDepartmentsWithReferences() throws Exception {
        when(departmentAdminService.listAll()).thenReturn(List.of(demoDepartment()));

        mockMvc.perform(get("/api/b/departments").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].campus_id").value(11))
                .andExpect(jsonPath("$[0].category_id").value(11))
                .andExpect(jsonPath("$[0].standard_department_id").value(1))
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
    void createRejectsMissingReferences() throws Exception {
        mockMvc.perform(post("/api/b/departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"name\": \"心血管内科\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns404WhenCampusMissing() throws Exception {
        when(departmentAdminService.create(any(Department.class))).thenThrow(new ApiException(404, "院区不存在"));

        mockMvc.perform(post("/api/b/departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("院区不存在"));
    }

    @Test
    void createReturns400WhenCampusAndCategoryBelongToDifferentHospitals() throws Exception {
        when(departmentAdminService.create(any(Department.class))).thenThrow(new ApiException(400, "院区与科室分类不属于同一医院"));

        mockMvc.perform(post("/api/b/departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("院区与科室分类不属于同一医院"));
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(departmentAdminService.update(any(Department.class))).thenThrow(new ApiException(404, "科室不存在"));

        mockMvc.perform(put("/api/b/departments/99")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("科室不存在"));
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

    @Test
    void deleteReturns409WhenDoctorsExist() throws Exception {
        doThrow(new ApiException(409, "科室下存在医生，无法删除"))
                .when(departmentAdminService)
                .delete(1L);

        mockMvc.perform(delete("/api/b/departments/1").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("科室下存在医生，无法删除"));
    }
}
