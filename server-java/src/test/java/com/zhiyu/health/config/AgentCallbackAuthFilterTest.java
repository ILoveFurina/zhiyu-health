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
    void recommendationCallbacksRequireTheSameCredential() throws Exception {
        // /api/agent/** 全量纳入保护：推荐类回调与挂号回调同一密钥
        assertThat(run("/api/agent/doctors/recommend", null)).isEqualTo(401);
        assertThat(run("/api/agent/doctors/recommend", "wrong")).isEqualTo(401);
        assertThat(run("/api/agent/doctors/recommend", "shared-secret")).isEqualTo(200);
        assertThat(run("/api/agent/hospitals/nearby", null)).isEqualTo(401);
        assertThat(run("/api/agent/hospitals/nearby", "shared-secret")).isEqualTo(200);
    }

    @Test
    void nonAgentPathsAreNotFiltered() throws Exception {
        assertThat(run("/api/c/chat", null)).isEqualTo(200);
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
