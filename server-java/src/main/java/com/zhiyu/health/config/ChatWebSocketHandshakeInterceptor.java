package com.zhiyu.health.config;

import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** 允许 WebSocket upgrade；若直连请求已有可信身份则兼容传入，隧道场景改由首帧认证。 */
@Component
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_PATIENT_ID = "patientId";

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        Object subject = servletRequest.getServletRequest().getAttribute(AuthFilter.ATTR_AUTH_SUBJECT);
        if (subject == null) {
            return true;
        }
        Long patientId;
        try {
            patientId = subject instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(subject));
        } catch (NumberFormatException invalidSubject) {
            return true;
        }
        attributes.put(ATTR_PATIENT_ID, patientId);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {}
}
