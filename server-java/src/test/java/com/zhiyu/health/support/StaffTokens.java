package com.zhiyu.health.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/** 测试用 staff 令牌：与 application.yml 演示占位密钥一致，真实走 AuthFilter */
public final class StaffTokens {

    private static final String DEV_SECRET = "zhiyu-dev-only-placeholder-secret";

    private StaffTokens() {
    }

    public static RequestPostProcessor withRole(String role) {
        return withSubject("1", role);
    }

    public static RequestPostProcessor withSubject(String staffId, String role) {
        String token = Jwts.builder()
                .subject(staffId)
                .claim("scope", "staff")
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(DEV_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
