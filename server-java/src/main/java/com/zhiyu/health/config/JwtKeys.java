package com.zhiyu.health.config;

import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;

/** HMAC-SHA 密钥派生唯一出口：B/C 端令牌的签发（service）与校验（AuthFilter）必须用同一派生方式。 */
public final class JwtKeys {

    private JwtKeys() {}

    public static SecretKey hmacShaKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
