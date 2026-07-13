package com.aisw.kkori.global.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 발급 설정 ({@code jwt.*}).
 *
 * <p>{@code secret}은 Access/Refresh Token이 공유하고(token_type claim으로 교차 사용 차단),
 * {@code signupSecret}은 signup token 전용 별도 키다. HS256 특성상 두 키 모두
 * 32바이트(256비트) 이상이어야 한다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        String signupSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration signupTokenTtl
) {
}
