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
import com.zhiyu.health.controller.b.mapping.StandardDepartmentInputMapperImpl;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.entity.StandardDepartment;
import com.zhiyu.health.service.StandardDepartmentAdminService;
import com.zhiyu.health.support.StaffTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 平台标准科室 CRUD seam（票 49）：科类+名称+排序输入、404/409 分支 */
@WebMvcTest(StandardDepartmentController.class)
@Import(StandardDepartmentInputMapperImpl.class)
class StandardDepartmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StandardDepartmentAdminService standardDepartmentAdminService;

    private static final String VALID_BODY =
            """
            {"category": "内科", "name": "心血管内科", "sort_order": 1}
            """;

    private StandardDepartment demoStandardDepartment() {
        StandardDepartment standardDepartment = new StandardDepartment();
        standardDepartment.setId(1L);
        standardDepartment.setCategory("内科");
        standardDepartment.setName("心血管内科");
        standardDepartment.setSortOrder(1);
        return standardDepartment;
    }

    @Test
    void listReturnsStandardDepartments() throws Exception {
        when(standardDepartmentAdminService.listAll()).thenReturn(List.of(demoStandardDepartment()));

        mockMvc.perform(get("/api/b/standard-departments").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].category").value("内科"))
                .andExpect(jsonPath("$[0].sort_order").value(1));
    }

    @Test
    void createRejectsDoctorRole() throws Exception {
        mockMvc.perform(post("/api/b/standard-departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createReturns201() throws Exception {
        when(standardDepartmentAdminService.create(any(StandardDepartment.class)))
                .thenReturn(demoStandardDepartment());

        mockMvc.perform(post("/api/b/standard-departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("心血管内科"));
    }

    @Test
    void createRejectsBlankCategory() throws Exception {
        mockMvc.perform(post("/api/b/standard-departments")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content("{\"category\": \"\", \"name\": \"心血管内科\", \"sort_order\": 1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(standardDepartmentAdminService.update(any(StandardDepartment.class)))
                .thenThrow(new ApiException(404, "标准科室不存在"));

        mockMvc.perform(put("/api/b/standard-departments/99")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("标准科室不存在"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/b/standard-departments/1").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        doThrow(new ApiException(404, "标准科室不存在"))
                .when(standardDepartmentAdminService)
                .delete(99L);

        mockMvc.perform(delete("/api/b/standard-departments/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("标准科室不存在"));
    }

    @Test
    void deleteReturns409WhenDepartmentsMapped() throws Exception {
        doThrow(new ApiException(409, "标准科室已被实际科室映射，无法删除"))
                .when(standardDepartmentAdminService)
                .delete(1L);

        mockMvc.perform(delete("/api/b/standard-departments/1").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("标准科室已被实际科室映射，无法删除"));
    }
}
