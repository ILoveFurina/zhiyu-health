package com.zhiyu.health.controller.c;

import com.zhiyu.health.entity.Patient;
import com.zhiyu.health.service.PatientService;
import com.zhiyu.health.service.PatientTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** C 端免注册 mock 登录 HTTP seam。 */
class CAuthControllerTest {

    @Test
    void mockLoginReturnsPatientAndScopedToken() throws Exception {
        PatientService patients = mock(PatientService.class);
        when(patients.mockLogin("阿珍")).thenReturn(new Patient(3L, "阿珍"));
        PatientTokenService tokens = new PatientTokenService(
                "test-secret-test-secret-test-secret", 720);
        MockMvc mvc = standaloneSetup(new CAuthController(patients, tokens)).build();

        mvc.perform(post("/api/c/auth/mock-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"阿珍\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.patient.id").value(3))
                .andExpect(jsonPath("$.patient.nickname").value("阿珍"));

        String token = tokens.issue(3L);
        assert tokens.verify(token) == 3L;
    }
}
