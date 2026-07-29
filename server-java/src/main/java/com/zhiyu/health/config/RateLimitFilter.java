package com.zhiyu.health.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * demo 级单机内存限流：固定窗口 60 秒，key 为已认证 subject（无则退回 remoteAddr）。
 * 由 WebConfig 装配（order 30，在 AuthFilter 之后，保证 subject 可读）。
 */
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;

    private final int permitsPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Object subject = request.getAttribute(AuthFilter.ATTR_AUTH_SUBJECT);
        String key = subject != null ? subject.toString() : request.getRemoteAddr();

        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (k, old) -> {
            if (old == null || now - old.start >= WINDOW_MS) {
                return new Window(now);
            }
            old.count.incrementAndGet();
            return old;
        });

        if (window.count.get() > permitsPerMinute) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\": \"请求过于频繁\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 单个窗口：起始时间 + 请求计数 */
    private static final class Window {
        private final long start;
        private final AtomicInteger count = new AtomicInteger(1);

        private Window(long start) {
            this.start = start;
        }
    }
}
