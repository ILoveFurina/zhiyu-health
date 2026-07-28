package com.zhiyu.health.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 保护携带患者身份的 Agent 工具回调，防止外部请求伪造运行时上下文。 */
public class AgentCallbackAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Agent-Callback-Token";
    private final byte[] expectedCredential;

    public AgentCallbackAuthFilter(String expectedCredential) {
        this.expectedCredential = expectedCredential.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/agent/appointments");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER_NAME);
        boolean valid = supplied != null && MessageDigest.isEqual(
                expectedCredential, supplied.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\": \"Agent 回调认证失败\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
