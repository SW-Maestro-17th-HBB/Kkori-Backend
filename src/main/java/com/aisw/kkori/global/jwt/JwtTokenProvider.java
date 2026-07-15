package com.aisw.kkori.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final SecretKey signupKey;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.signupKey = Keys.hmacShaKeyFor(properties.signupSecret().getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
        this.clock = clock;
    }

    public String createAccessToken(Long userId) {
        Instant now = clock.instant();
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
        return createSignupToken(providerId, email, nickname, null);
    }

    /**
     * 탈퇴 건 바인딩 signup token 발급 — {@code deletionLogId} claim으로 특정 탈퇴 요청 건에
     * 바인딩해 제출 시 잠금 하 재판정(복구/만료 신규 생성/409/401)을 강제한다. 무저장 토큰은
     * 개별 무효화가 불가능하므로, 바인딩 없이는 복구 후 재탈퇴한 계정을 옛 토큰으로 되돌리거나
     * 배치의 파기 선점을 우회할 수 있다(PRD account.md 기능 4).
     */
    public String createSignupToken(String providerId, String email, String nickname, Long deletionLogId) {
        Instant now = clock.instant();
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
        if (deletionLogId != null) {
            builder.claim("deletion_log_id", deletionLogId);
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
        // jjwt는 숫자 claim을 크기에 따라 Integer로 역직렬화할 수 있어 Number로 받아 변환한다
        Number deletionLogId = claims.get("deletion_log_id", Number.class);
        return new SignupClaims(
                claims.get("provider_id", String.class),
                claims.get("email", String.class),
                claims.get("nickname", String.class),
                deletionLogId == null ? null : deletionLogId.longValue()
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

    /**
     * signup token이 운반하는 신원 정보. 카카오 원천 정보가 users로 가는 유일한 통로다.
     * {@code deletionLogId}의 유무가 토큰 용도를 가른다 — 있으면 탈퇴 건 바인딩
     * (제출 시 상태·유예를 잠금 하에 재판정해 복구 또는 신규 생성), 없으면 신규 가입용.
     */
    public record SignupClaims(String providerId, String email, String nickname, Long deletionLogId) {
    }
}
