package com.zhiyu.health.config;

import com.zhiyu.health.service.PatientTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** C 端令牌 scope seam：可访问 C 端接口但不能混用到 B 端。 */
class PatientScopeAuthTest {

    private static final String SECRET = "test-secret-test-secret-test-secret";

    @Test
    void patientTokenIsLimitedToPatientApi() throws Exception {
        String token = new PatientTokenService(SECRET, 720).issue(3L);
        AuthFilter filter = new AuthFilter(SECRET);

        MockHttpServletRequest patientRequest = request("/api/c/probe", token);
        MockHttpServletResponse patientResponse = new MockHttpServletResponse();
        MockFilterChain patientChain = new MockFilterChain();
        filter.doFilter(patientRequest, patientResponse, patientChain);

        assertThat(patientResponse.getStatus()).isEqualTo(200);
        assertThat(patientChain.getRequest().getAttribute(AuthFilter.ATTR_AUTH_SUBJECT))
                .isEqualTo("3");

        MockHttpServletResponse staffResponse = new MockHttpServletResponse();
        filter.doFilter(request("/api/b/probe", token), staffResponse, new MockFilterChain());
        assertThat(staffResponse.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
