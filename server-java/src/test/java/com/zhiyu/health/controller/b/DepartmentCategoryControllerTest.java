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
import com.zhiyu.health.controller.staff.organization.DepartmentCategoryController;
import com.zhiyu.health.controller.staff.organization.mapping.DepartmentCategoryInputMapperImpl;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.organization.DepartmentCategory;
import com.zhiyu.health.service.organization.DepartmentCategoryAdminService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 医院科室分类 CRUD seam（票 49）：医院外键 404、删除 409 */
@WebMvcTest(DepartmentCategoryController.class)
@Import(DepartmentCategoryInputMapperImpl.class)
class DepartmentCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepartmentCategoryAdminService departmentCategoryAdminService;

    private static final String VALID_BODY =
            """
            {"campus_id": 11, "name": "内科", "sort_order": 1}
            """;

    private DepartmentCategory demoCategory() {
        DepartmentCategory category = new DepartmentCategory();
        category.setId(11L);
        category.setCampusId(11L);
        category.setName("内科");
        category.setSortOrder(1);
        return category;
    }

    @Test
    void listReturnsCategories() throws Exception {
        when(departmentCategoryAdminService.listAll()).thenReturn(List.of(demoCategory()));

        mockMvc.perform(get("/api/b/department-categories").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[0].campus_id").value(11))
                .andExpect(jsonPath("$[0].sort_order").value(1));
    }

    @Test
    void createRejectsDoctorRole() throws Exception {
        mockMvc.perform(post("/api/b/department-categories")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createReturns201() throws Exception {
        when(departmentCategoryAdminService.create(any(DepartmentCategory.class)))
                .thenReturn(demoCategory());

        mockMvc.perform(post("/api/b/department-categories")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("内科"));
    }

    @Test
    void createRejectsMissingCampusId() throws Exception {
        mockMvc.perform(post("/api/b/department-categories")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"name\": \"内科\", \"sort_order\": 1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns404WhenCampusMissing() throws Exception {
        when(departmentCategoryAdminService.create(any(DepartmentCategory.class)))
                .thenThrow(new ApiException(404, "院区不存在"));

        mockMvc.perform(post("/api/b/department-categories")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("院区不存在"));
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(departmentCategoryAdminService.update(any(DepartmentCategory.class)))
                .thenThrow(new ApiException(404, "科室分类或院区不存在"));

        mockMvc.perform(put("/api/b/department-categories/99")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("科室分类或院区不存在"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/b/department-categories/11").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        doThrow(new ApiException(404, "科室分类不存在"))
                .when(departmentCategoryAdminService)
                .delete(99L);

        mockMvc.perform(delete("/api/b/department-categories/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("科室分类不存在"));
    }

    @Test
    void deleteReturns409WhenDepartmentsExist() throws Exception {
        doThrow(new ApiException(409, "科室分类下存在科室，无法删除"))
                .when(departmentCategoryAdminService)
                .delete(11L);

        mockMvc.perform(delete("/api/b/department-categories/11").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("科室分类下存在科室，无法删除"));
    }
}
