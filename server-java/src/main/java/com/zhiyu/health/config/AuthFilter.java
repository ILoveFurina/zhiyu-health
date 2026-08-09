package com.zhiyu.health.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import javax.crypto.SecretKey;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 鉴权：只拦 /api/c/** 与 /api/b/**，放行 /api/health 与登录端点。
 * 不作为 @Component 注册，统一由 WebConfig 的 FilterRegistrationBean 装配（order 20，先于限流，
 * 使限流能按已认证 subject 计键）。
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_AUTH_SUBJECT = "authSubject";
    /** B 端角色（admin/doctor），供 controller 做角色判断 */
    public static final String ATTR_AUTH_ROLE = "authRole";

    private final SecretKey key;

    public AuthFilter(String jwtSecret) {
        this.key = JwtKeys.hmacShaKey(jwtSecret);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 登录端点本身不持令牌，放行
        if (path.startsWith("/api/c/auth/") || path.startsWith("/api/b/auth/login")) {
            return true;
        }
        // WebSocket upgrade 可能被 cpolar 等隧道重建并剥离 Authorization；只放行 HTTP
        // 握手，患者 JWT 必须在连接后的首个 auth 信封中校验，未认证会话不能发送 chat。
        if ("/api/c/chat/ws".equals(path)) {
            return true;
        }
        // 图片代理端点放行：支付宝 <image src> 组件不带 Authorization header，
        // 以 object_key 的 UUID 不可猜测性作为取图凭证（ADR-0023 demo 场景）。
        if (path.startsWith("/api/c/photos")) {
            return true;
        }
        return !path.startsWith("/api/c/") && !path.startsWith("/api/b/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = stripSimulatorQuotes(request.getHeader("Authorization"));
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (token == null) {
            reject(response);
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String path = request.getRequestURI();
            String requiredScope = path.startsWith("/api/c/") ? "c_patient" : "staff";
            if (!requiredScope.equals(claims.get("scope", String.class))) {
                reject(response);
                return;
            }

            request.setAttribute(ATTR_AUTH_SUBJECT, claims.getSubject());
            request.setAttribute(ATTR_AUTH_ROLE, claims.get("role", String.class));
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            reject(response);
        }
    }

    /**
     * 支付宝开发者工具会把 connectSocket 的 header 参数值整体包一层字面双引号
     * （JS→原生桥 JSON 序列化的副作用），导致按严格语法解析失败；真机与标准客户端发出的是
     * 干净头，剥离成对外层引号对其为 no-op。
     */
    private static String stripSimulatorQuotes(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void reject(HttpServletResponse response) throws IOException {
        ApiErrorBody.write(response, HttpServletResponse.SC_UNAUTHORIZED, "未认证或令牌无效");
    }
}
