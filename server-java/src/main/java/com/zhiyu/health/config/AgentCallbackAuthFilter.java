package com.zhiyu.health.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.filter.OncePerRequestFilter;

/** 保护全部 Agent 工具回调（/api/agent/**），防止外部请求伪造运行时上下文。 */
public class AgentCallbackAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Agent-Callback-Token";
    public static final String PATIENT_ID_HEADER = "X-Agent-Patient-Id";
    public static final String PATIENT_SIGNATURE_HEADER = "X-Agent-Patient-Signature";
    public static final String ATTR_PATIENT_ID = "agentPatientId";
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
        if (request.getRequestURI().equals("/api/agent/contraindications/check")
                && !mountVerifiedPatientContext(request)) {
            ApiErrorBody.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Agent 患者上下文认证失败");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean mountVerifiedPatientContext(HttpServletRequest request) {
        String patientIdText = request.getHeader(PATIENT_ID_HEADER);
        String suppliedSignature = request.getHeader(PATIENT_SIGNATURE_HEADER);
        if (patientIdText == null || suppliedSignature == null) {
            return false;
        }
        try {
            long patientId = Long.parseLong(patientIdText);
            if (patientId <= 0
                    || !MessageDigest.isEqual(
                            sign(patientIdText), HexFormat.of().parseHex(suppliedSignature))) {
                return false;
            }
            request.setAttribute(ATTR_PATIENT_ID, patientId);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private byte[] sign(String patientId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(expectedCredential, "HmacSHA256"));
            return mac.doFinal(patientId.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 不可用", impossible);
        }
    }
}
