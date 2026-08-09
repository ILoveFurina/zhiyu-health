package com.zhiyu.health.service.common;

import com.zhiyu.health.config.JwtKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 签发并校验带 c_patient scope 的 C 端 JWT，防止 B/C 端令牌混用。 */
@Service
public class PatientTokenService {

    public static final String SCOPE = "c_patient";

    private final SecretKey key;
    private final int expireMinutes;

    public PatientTokenService(
            @Value("${zhiyu.jwt-secret}") String secret,
            @Value("${zhiyu.patient-token-expire-minutes:720}") int expireMinutes) {
        this.key = JwtKeys.hmacShaKey(secret);
        this.expireMinutes = expireMinutes;
    }

    public String issue(Long patientId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(patientId.toString())
                .claim("scope", SCOPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** WebSocket 首帧认证复用同一密钥与 scope 纪律；无效值由调用方统一转认证失败。 */
    public Long verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("患者令牌不能为空");
        }
        Claims claims =
                Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!SCOPE.equals(claims.get("scope", String.class))) {
            throw new IllegalArgumentException("患者令牌 scope 无效");
        }
        return Long.valueOf(claims.getSubject());
    }
}
