package com.zhiyu.health.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/** 签发带 c_patient scope 的 C 端 JWT，防止 B/C 端令牌混用。 */
@Service
public class PatientTokenService {

    public static final String SCOPE = "c_patient";

    private final SecretKey key;
    private final int expireMinutes;

    public PatientTokenService(
            @Value("${zhiyu.jwt-secret}") String secret,
            @Value("${zhiyu.patient-token-expire-minutes:720}") int expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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

    public Long verify(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        if (!SCOPE.equals(claims.get("scope", String.class))) {
            throw new IllegalArgumentException("令牌域不符");
        }
        return Long.valueOf(claims.getSubject());
    }
}
