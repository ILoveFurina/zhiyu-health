package com.zhiyu.health.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

class ChatWebSocketHandshakeInterceptorTest {

    private final ChatWebSocketHandshakeInterceptor interceptor = new ChatWebSocketHandshakeInterceptor();

    @Test
    void handshakeUsesIdentityMountedByAuthorizationFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/c/chat/ws");
        request.setAttribute(AuthFilter.ATTR_AUTH_SUBJECT, "12");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(ChatWebSocketHandshakeInterceptor.ATTR_PATIENT_ID, 12L);
    }

    @Test
    void unauthenticatedUpgradePassesWithoutTrustingQueryToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/c/chat/ws");
        request.setQueryString("token=must-not-be-used");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).doesNotContainKey(ChatWebSocketHandshakeInterceptor.ATTR_PATIENT_ID);
    }
}
