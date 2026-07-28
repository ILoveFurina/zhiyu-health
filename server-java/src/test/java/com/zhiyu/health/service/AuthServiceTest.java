package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.StaffUserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 认证逻辑：口令校验、令牌 claims（scope=staff + role，AuthFilter 依赖） */
class AuthServiceTest {

    private static final String SECRET = "test-secret-key-0123456789abcdef0123456789";

    private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AuthService authService =
            new AuthService(staffUserMapper, passwordEncoder, SECRET, 480);

    private StaffUser storedStaff(String password) {
        StaffUser staff = new StaffUser();
        staff.setId(1L);
        staff.setUsername("admin");
        staff.setPasswordHash(passwordEncoder.encode(password));
        staff.setRole(StaffUser.ROLE_ADMIN);
        return staff;
    }

    @Test
    void authenticateReturnsStaffWhenPasswordMatches() {
        when(staffUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(storedStaff("pass"));

        assertThat(authService.authenticate("admin", "pass")).isNotNull();
    }

    @Test
    void authenticateReturnsNullWhenPasswordWrong() {
        when(staffUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(storedStaff("pass"));

        assertThat(authService.authenticate("admin", "bad")).isNull();
    }

    @Test
    void authenticateReturnsNullWhenUserUnknown() {
        when(staffUserMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThat(authService.authenticate("ghost", "pass")).isNull();
    }

    @Test
    void tokenCarriesStaffScopeAndRole() {
        String token = authService.createAccessToken(storedStaff("pass"));

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("scope", String.class)).isEqualTo("staff");
        assertThat(claims.get("role", String.class)).isEqualTo("admin");
        assertThat(claims.getExpiration()).isNotNull();
    }
}
