package com.zhiyu.health.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** JWT 鉴权：scope 分端校验、role 透传、登录端点放行、/me 必须持令牌 */
class AuthFilterTest {

    private static final String SECRET = "test-secret-key-0123456789abcdef0123456789";
    private final AuthFilter filter = new AuthFilter(SECRET);

    private String token(Map<String, Object> claims) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("1")
                .claims(claims)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();
    }

    private MockHttpServletResponse run(String path, String bearerToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (bearerToken != null) {
            request.addHeader("Authorization", "Bearer " + bearerToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest() != null).isEqualTo(response.getStatus() == 200);
        return response;
    }

    @Test
    void staffTokenPassesAndRoleIsExposed() throws Exception {
        String bearer = token(Map.of("scope", "staff", "role", "admin"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/b/hospitals");
        request.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain.getRequest().getAttribute(AuthFilter.ATTR_AUTH_SUBJECT)).isEqualTo("1");
        assertThat(chain.getRequest().getAttribute(AuthFilter.ATTR_AUTH_ROLE)).isEqualTo("admin");
    }

    @Test
    void patientScopeRejectedOnStaffEndpoints() throws Exception {
        MockHttpServletResponse response = run("/api/b/hospitals", token(Map.of("scope", "c_patient")));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void loginEndpointPassesWithoutToken() throws Exception {
        assertThat(run("/api/b/auth/login", null).getStatus()).isEqualTo(200);
    }

    @Test
    void meEndpointRequiresToken() throws Exception {
        assertThat(run("/api/b/auth/me", null).getStatus()).isEqualTo(401);
    }
}
