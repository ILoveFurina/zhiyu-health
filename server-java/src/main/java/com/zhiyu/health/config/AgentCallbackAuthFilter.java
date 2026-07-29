package com.zhiyu.health.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** 保护全部 Agent 工具回调（/api/agent/**），防止外部请求伪造运行时上下文。 */
public class AgentCallbackAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Agent-Callback-Token";
    private final byte[] expectedCredential;

    public AgentCallbackAuthFilter(String expectedCredential) {
        this.expectedCredential = expectedCredential.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/agent/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER_NAME);
        boolean valid = supplied != null
                && MessageDigest.isEqual(expectedCredential, supplied.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            ApiErrorBody.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Agent 回调认证失败");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
