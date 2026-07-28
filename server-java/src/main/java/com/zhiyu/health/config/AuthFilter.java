package com.zhiyu.health.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权：只拦 /api/c/** 与 /api/b/**，放行 /api/health 与登录端点。
 * 不作为 @Component 注册，统一由 WebConfig 的 FilterRegistrationBean 装配（order 20，先于限流，
 * 使限流能按已认证 subject 计键）。
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_AUTH_SUBJECT = "authSubject";

    private final SecretKey key;

    public AuthFilter(String jwtSecret) {
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 登录端点属后续票，先放行
        if (path.startsWith("/api/c/auth/") || path.startsWith("/api/b/auth/")) {
            return true;
        }
        return !path.startsWith("/api/c/") && !path.startsWith("/api/b/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response);
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(header.substring(7))
                    .getPayload();

            String path = request.getRequestURI();
            String requiredScope = path.startsWith("/api/c/") ? "c_patient" : "staff";
            if (!requiredScope.equals(claims.get("scope", String.class))) {
                reject(response);
                return;
            }

            request.setAttribute(ATTR_AUTH_SUBJECT, claims.getSubject());
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            reject(response);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\": \"未认证或令牌无效\"}");
    }
}
