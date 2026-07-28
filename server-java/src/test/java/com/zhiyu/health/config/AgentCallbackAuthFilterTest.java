package com.zhiyu.health.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCallbackAuthFilterTest {

    private final AgentCallbackAuthFilter filter = new AgentCallbackAuthFilter("shared-secret");

    @Test
    void patientAppointmentCallbackRequiresSharedServiceCredential() throws Exception {
        assertThat(run("/api/agent/appointments", null)).isEqualTo(401);
        assertThat(run("/api/agent/appointments", "wrong")).isEqualTo(401);
        assertThat(run("/api/agent/appointments", "shared-secret")).isEqualTo(200);
    }

    @Test
    void nonPatientAgentReadsRemainAvailable() throws Exception {
        assertThat(run("/api/agent/doctors/recommend", null)).isEqualTo(200);
    }

    private int run(String path, String credential) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (credential != null) {
            request.addHeader(AgentCallbackAuthFilter.HEADER_NAME, credential);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
