package com.aisw.kkori.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final SecretKey signupKey;
    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.signupKey = Keys.hmacShaKeyFor(properties.signupSecret().getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
    }

    public String createAccessToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .claim(TokenType.CLAIM_NAME, TokenType.ACCESS.getValue())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Refresh Token 발급·재생성. 같은 입력이면 항상 같은 문자열을 반환한다.
     * 발급 시엔 호출부가 {@code expiresAt = issuedAt + TTL}을 계산해 전달하고,
     * Grace Period 재생성 시엔 DB에 저장된 {@code created_at}·{@code expired_at}을 그대로 전달한다.
     */
    public String createRefreshToken(Long userId, String jti, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim(TokenType.CLAIM_NAME, TokenType.REFRESH.getValue())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * signup token 발급 — 카카오에서 받은 신원을 동의 완료 시점까지 서명으로 운반한다(서버 미저장).
     * email·nickname이 null이면 claim 자체를 생략한다.
     */
    public String createSignupToken(String providerId, String email, String nickname) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.signupTokenTtl())))
                .claim("provider_id", providerId)
                .claim(TokenType.CLAIM_NAME, TokenType.SIGNUP.getValue());
        if (email != null) {
            builder.claim("email", email);
        }
        if (nickname != null) {
            builder.claim("nickname", nickname);
        }
        return builder.signWith(signupKey, Jwts.SIG.HS256).compact();
    }

    /**
     * Access Token 검증 후 userId를 반환한다.
     * 서명·만료·token_type 불일치 시 {@link JwtException}을 던진다.
     */
    public Long parseAccessToken(String token) {
        Claims claims = parse(key, token, TokenType.ACCESS);
        return Long.valueOf(claims.getSubject());
    }

    /** signup token 검증 후 신원 claim을 반환한다. 실패 시 {@link JwtException}을 던진다. */
    public SignupClaims parseSignupToken(String token) {
        Claims claims = parse(signupKey, token, TokenType.SIGNUP);
        return new SignupClaims(
                claims.get("provider_id", String.class),
                claims.get("email", String.class),
                claims.get("nickname", String.class)
        );
    }

    private Claims parse(SecretKey secretKey, String token, TokenType expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String tokenType = claims.get(TokenType.CLAIM_NAME, String.class);
        if (!expectedType.getValue().equals(tokenType)) {
            throw new JwtException("token_type이 %s가 아닙니다".formatted(expectedType.getValue()));
        }
        return claims;
    }

    /** signup token이 운반하는 신원 정보. 카카오 원천 정보가 users로 가는 유일한 통로다. */
    public record SignupClaims(String providerId, String email, String nickname) {
    }
}
