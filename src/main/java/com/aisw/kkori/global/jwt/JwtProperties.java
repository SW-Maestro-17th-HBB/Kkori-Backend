package com.aisw.kkori.global.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT 발급 설정 ({@code jwt.*}).
 *
 * <p>{@code secret}은 Access/Refresh Token이 공유하고(token_type claim으로 교차 사용 차단),
 * {@code signupSecret}은 signup token 전용 별도 키다. HS256 특성상 두 키 모두
 * 32바이트(256비트) 이상이어야 한다.
 *
 * <p>잘못된 설정(짧은 키, 0 이하 TTL)은 토큰 발급 시점이 아니라 부팅 시점에 실패시킨다(fail-fast).
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        String signupSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration signupTokenTtl
) {

    private static final int MIN_KEY_BYTES = 32;

    public JwtProperties {
        requireKey(secret, "jwt.secret");
        requireKey(signupSecret, "jwt.signup-secret");
        requirePositive(accessTokenTtl, "jwt.access-token-ttl");
        requirePositive(refreshTokenTtl, "jwt.refresh-token-ttl");
        requirePositive(signupTokenTtl, "jwt.signup-token-ttl");
    }

    private static void requireKey(String key, String name) {
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "%s은(는) HS256 요건상 %d바이트 이상이어야 합니다".formatted(name, MIN_KEY_BYTES));
        }
    }

    private static void requirePositive(Duration ttl, String name) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("%s은(는) 0보다 큰 기간이어야 합니다".formatted(name));
        }
    }
}
