package com.zhiyu.health.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 演示重置冻结过滤器（票 25，ADR-0020）：重置进行中拦截全部 C 端入口返回 503，
 * B 端只读与重置接口本身不受影响。由 WebConfig 装配（order 25，鉴权 20 之后、限流 30 之前）。
 *
 * 冻结为瞬态：重置完成由 {@link DemoFreezeGate#unfreeze()} 解冻；中途失败保持冻结，
 * 演示者可从 B 端观察断言结果后重跑（接口幂等可从失败步续跑，不自动回滚）。
 */
@RequiredArgsConstructor
public class DemoFreezeFilter extends OncePerRequestFilter {

    private final DemoFreezeGate freezeGate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (freezeGate.isFrozen()) {
            ApiErrorBody.write(response, 503, "演示重置中，请稍后重试");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
