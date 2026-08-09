package com.zhiyu.health.controller.b;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.controller.staff.common.BAuthController;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.service.common.AuthService;
import com.zhiyu.health.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** B 端认证 seam：登录签发令牌、/me 返回资料；字段锁 snake_case（票 02 契约） */
@WebMvcTest(BAuthController.class)
class BAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    private StaffUser adminStaff() {
        StaffUser staff = new StaffUser();
        staff.setId(1L);
        staff.setUsername("admin");
        staff.setRole(StaffUser.ROLE_ADMIN);
        return staff;
    }

    @Test
    void loginReturnsTokenWhenCredentialsValid() throws Exception {
        StaffUser staff = adminStaff();
        when(authService.authenticate("admin", "pass")).thenReturn(staff);
        when(authService.createAccessToken(staff)).thenReturn("signed-token");

        mockMvc.perform(post("/api/b/auth/login")
                        .contentType("application/json")
                        .content("{\"username\": \"admin\", \"password\": \"pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("signed-token"))
                .andExpect(jsonPath("$.token_type").value("bearer"));
    }

    @Test
    void loginRejectsWrongCredentials() throws Exception {
        when(authService.authenticate("admin", "bad")).thenReturn(null);

        mockMvc.perform(post("/api/b/auth/login")
                        .contentType("application/json")
                        .content("{\"username\": \"admin\", \"password\": \"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("账号或密码错误"));
    }

    @Test
    void loginRejectsBlankUsername() throws Exception {
        mockMvc.perform(post("/api/b/auth/login")
                        .contentType("application/json")
                        .content("{\"username\": \"\", \"password\": \"pass\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meReturnsProfile() throws Exception {
        StaffUser staff = new StaffUser();
        staff.setUsername("doctor.lin");
        staff.setRole(StaffUser.ROLE_DOCTOR);
        staff.setDoctorId(1L);
        when(authService.profile(7L)).thenReturn(staff);

        mockMvc.perform(get("/api/b/auth/me").with(StaffTokens.withSubject("7", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("doctor.lin"))
                .andExpect(jsonPath("$.role").value("doctor"))
                .andExpect(jsonPath("$.doctor_id").value(1));
    }

    @Test
    void meRejectsDeletedAccount() throws Exception {
        when(authService.profile(7L)).thenReturn(null);

        mockMvc.perform(get("/api/b/auth/me").with(StaffTokens.withSubject("7", StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("登录已失效"));
    }

    @Test
    void meRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/b/auth/me")).andExpect(status().isUnauthorized());
    }
}
