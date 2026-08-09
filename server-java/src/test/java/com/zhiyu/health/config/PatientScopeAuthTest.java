package com.zhiyu.health.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.service.common.PatientTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端令牌 scope seam：可访问 C 端接口但不能混用到 B 端。 */
class PatientScopeAuthTest {

    private static final String SECRET = "test-secret-test-secret-test-secret";

    @Test
    void patientTokenIsLimitedToPatientApi() throws Exception {
        String token = new PatientTokenService(SECRET, 720).issue(3L);
        MockMvc mvc = standaloneSetup(new ProbeController())
                .addFilters(new AuthFilter(SECRET))
                .build();

        mvc.perform(get("/api/c/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("3"));
        mvc.perform(get("/api/b/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/c/probe")
        String patient(jakarta.servlet.http.HttpServletRequest request) {
            return String.valueOf(request.getAttribute(AuthFilter.ATTR_AUTH_SUBJECT));
        }

        @GetMapping("/api/b/probe")
        String staff() {
            return "staff";
        }
    }
}
