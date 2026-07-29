package com.zhiyu.health.controller.b;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.controller.b.mapping.DoctorInputMapperImpl;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.service.DoctorAdminService;
import com.zhiyu.health.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 医生 CRUD seam：photo_url snake_case 与科室外键 404 */
@WebMvcTest(DoctorController.class)
@Import(DoctorInputMapperImpl.class)
class DoctorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DoctorAdminService doctorAdminService;

    private static final String VALID_BODY = """
            {"department_id": 1, "name": "林知远", "title": "主任医师",
             "specialty": "高血压、冠心病", "photo_url": "https://example.com/demo/lin.jpg"}
            """;

    private Doctor demoDoctor() {
        Doctor doctor = new Doctor();
        doctor.setId(1L);
        doctor.setDepartmentId(1L);
        doctor.setName("林知远");
        doctor.setTitle("主任医师");
        doctor.setSpecialty("高血压、冠心病");
        doctor.setPhotoUrl("https://example.com/demo/lin.jpg");
        return doctor;
    }

    @Test
    void listReturnsDoctors() throws Exception {
        when(doctorAdminService.listAll()).thenReturn(List.of(demoDoctor()));

        mockMvc.perform(get("/api/b/doctors").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].department_id").value(1))
                .andExpect(jsonPath("$[0].photo_url").value("https://example.com/demo/lin.jpg"));
    }

    @Test
    void createRejectsDoctorRole() throws Exception {
        mockMvc.perform(post("/api/b/doctors").with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR))
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void createReturns201() throws Exception {
        when(doctorAdminService.create(any(Doctor.class))).thenReturn(demoDoctor());

        mockMvc.perform(post("/api/b/doctors").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("林知远"));
    }

    @Test
    void createReturns404WhenDepartmentMissing() throws Exception {
        when(doctorAdminService.create(any(Doctor.class)))
                .thenThrow(new ApiException(404, "科室不存在"));

        mockMvc.perform(post("/api/b/doctors").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("科室不存在"));
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(doctorAdminService.update(any(Doctor.class)))
                .thenThrow(new ApiException(404, "医生或科室不存在"));

        mockMvc.perform(put("/api/b/doctors/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN))
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("医生或科室不存在"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/b/doctors/1").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        doThrow(new ApiException(404, "医生不存在")).when(doctorAdminService).delete(99L);

        mockMvc.perform(delete("/api/b/doctors/99").with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("医生不存在"));
    }
}
