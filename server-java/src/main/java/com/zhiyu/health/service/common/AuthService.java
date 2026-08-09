package com.zhiyu.health.service.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.config.JwtKeys;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** B 端认证：校验口令、签发 scope=staff 的 JWT；令牌校验统一在 AuthFilter */
@Service
public class AuthService {

    private final StaffUserMapper staffUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey jwtKey;
    private final Duration tokenTtl;

    public AuthService(
            StaffUserMapper staffUserMapper,
            PasswordEncoder passwordEncoder,
            @Value("${zhiyu.jwt-secret}") String jwtSecret,
            @Value("${zhiyu.jwt-expire-minutes}") long jwtExpireMinutes) {
        this.staffUserMapper = staffUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtKey = JwtKeys.hmacShaKey(jwtSecret);
        this.tokenTtl = Duration.ofMinutes(jwtExpireMinutes);
    }

    /** 口令校验通过返回员工，否则 null */
    public StaffUser authenticate(String username, String password) {
        StaffUser staff = staffUserMapper.selectOne(new QueryWrapper<StaffUser>().eq("username", username));
        if (staff == null || !passwordEncoder.matches(password, staff.getPasswordHash())) {
            return null;
        }
        return staff;
    }

    /** 签发 JWT：sub=员工 id，scope=staff（AuthFilter 按 scope 分端），role 供 B 端鉴权 */
    public String createAccessToken(StaffUser staff) {
        Instant expiresAt = Instant.now().plus(tokenTtl);
        return Jwts.builder()
                .subject(String.valueOf(staff.getId()))
                .claim("scope", "staff")
                .claim("role", staff.getRole())
                .expiration(Date.from(expiresAt))
                .signWith(jwtKey)
                .compact();
    }

    /** 按令牌 subject 取员工资料（账号被删返回 null） */
    public StaffUser profile(long staffId) {
        return staffUserMapper.selectById(staffId);
    }
}
