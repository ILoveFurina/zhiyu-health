package com.zhiyu.health.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 审计日志：最外层（order 10），401/429 也要落审计。
 * 硬规则 6：只落脱敏摘要（方法/路径/状态码/耗时/身份标识/请求体长度），绝不记录请求体。
 */
public class AuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long costMs = System.currentTimeMillis() - start;
            Object subject = request.getAttribute(AuthFilter.ATTR_AUTH_SUBJECT);
            log.info("audit method={} path={} status={} costMs={} subject={} reqLen={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    costMs,
                    subject,
                    request.getContentLengthLong());
        }
    }
}
