package com.aisw.kkori.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "test-jwt-secret-key-for-unit-tests-32bytes!";
    private static final String SIGNUP_SECRET = "test-signup-secret-key-for-unit-tests-32b!";

    /**
     * 고정 Clock — 발급 시각이 주입된 Clock을 따르는지 결정적으로 검증한다.
     * 과거의 임의 고정 시각을 쓰면 jjwt 파서가 실제 시스템 시각으로 만료를 검증해
     * 파싱 테스트가 전부 만료 실패하므로, 실행 시점을 초 단위로 절삭해 고정한다
     * (JWT iat/exp는 초 정밀도).
     */
    private static final Instant FIXED_NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private final JwtProperties properties = new JwtProperties(
            SECRET, SIGNUP_SECRET, Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(10));
    private final JwtTokenProvider provider =
            new JwtTokenProvider(properties, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("AT payload는 sub·iat·exp·token_type만 담는다 — 개인정보 미포함")
    void accessTokenContainsOnlyMinimalClaims() {
        String accessToken = provider.createAccessToken(1L);

        Claims claims = parseWith(SECRET, accessToken);
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "iat", "exp", "token_type");
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("token_type")).isEqualTo("access");
    }

    @Test
    @DisplayName("AT의 iat은 주입된 Clock의 시각과 정확히 일치한다 — 발급 시각 결정성")
    void accessTokenIatMatchesInjectedClock() {
        Claims claims = parseWith(SECRET, provider.createAccessToken(1L));

        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(FIXED_NOW);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("AT는 30분, signup token은 10분 만료로 발급된다")
    void tokenTtl() {
        Claims access = parseWith(SECRET, provider.createAccessToken(1L));
        assertThat(access.getExpiration().getTime() - access.getIssuedAt().getTime())
                .isEqualTo(Duration.ofMinutes(30).toMillis());

        Claims signup = parseWith(SIGNUP_SECRET, provider.createSignupToken("pid", "e@x.com", "nick"));
        assertThat(signup.getExpiration().getTime() - signup.getIssuedAt().getTime())
                .isEqualTo(Duration.ofMinutes(10).toMillis());
    }

    @Test
    @DisplayName("RT는 iat 기준으로 호출부가 전달한 만료(14일)를 그대로 담는다")
    void refreshTokenTtl() {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(Duration.ofDays(14));

        Claims claims = parseWith(SECRET, provider.createRefreshToken(1L, "jti-1", issuedAt, expiresAt));
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(issuedAt);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(expiresAt);
        assertThat(claims.get("token_type")).isEqualTo("refresh");
    }

    @Test
    @DisplayName("RT 생성은 결정적이다 — 같은 재료면 같은 문자열·같은 해시")
    void refreshTokenIsDeterministic() {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(Duration.ofDays(14));

        String first = provider.createRefreshToken(42L, "fixed-jti", issuedAt, expiresAt);
        String second = provider.createRefreshToken(42L, "fixed-jti", issuedAt, expiresAt);

        assertThat(second).isEqualTo(first);
        assertThat(TokenHasher.sha256Hex(second)).isEqualTo(TokenHasher.sha256Hex(first));
    }

    @Test
    @DisplayName("위변조된 AT는 거부된다")
    void tamperedTokenIsRejected() {
        String tampered = provider.createAccessToken(1L) + "x";

        assertThatThrownBy(() -> provider.parseAccessToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("만료된 AT는 거부된다")
    void expiredTokenIsRejected() {
        String expired = Jwts.builder()
                .subject("1")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .claim(TokenType.CLAIM_NAME, TokenType.ACCESS.getValue())
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> provider.parseAccessToken(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("32바이트 미만 서명 키는 부팅 시점(설정 바인딩)에 거부된다")
    void shortSecretIsRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtProperties(
                "too-short", SIGNUP_SECRET, Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    @DisplayName("0 이하의 TTL은 부팅 시점(설정 바인딩)에 거부된다")
    void nonPositiveTtlIsRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtProperties(
                SECRET, SIGNUP_SECRET, Duration.ZERO, Duration.ofDays(14), Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.access-token-ttl");

        assertThatThrownBy(() -> new JwtProperties(
                SECRET, SIGNUP_SECRET, Duration.ofMinutes(30), Duration.ofSeconds(-1), Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.refresh-token-ttl");

        assertThatThrownBy(() -> new JwtProperties(
                SECRET, SIGNUP_SECRET, Duration.ofMinutes(30), Duration.ofDays(14), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.signup-token-ttl");
    }

    @Test
    @DisplayName("signup token을 AT로 쓰면 거부된다 — 별도 키 + token_type 이중 차단")
    void signupTokenCannotBeUsedAsAccessToken() {
        String signupToken = provider.createSignupToken("pid", null, null);

        assertThatThrownBy(() -> provider.parseAccessToken(signupToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("같은 키로 서명된 RT라도 token_type 검증으로 AT 오용이 차단된다")
    void refreshTokenCannotBeUsedAsAccessToken() {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String refreshToken = provider.createRefreshToken(1L, "jti", issuedAt, issuedAt.plus(Duration.ofDays(14)));

        assertThatThrownBy(() -> provider.parseAccessToken(refreshToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("AT를 signup token으로 쓰면 거부된다")
    void accessTokenCannotBeUsedAsSignupToken() {
        String accessToken = provider.createAccessToken(1L);

        assertThatThrownBy(() -> provider.parseSignupToken(accessToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("email·nickname이 null이면 claim 자체를 생략하고, 파싱 결과도 null이다")
    void nullClaimsAreOmitted() {
        String signupToken = provider.createSignupToken("pid-1", null, null);

        Claims claims = parseWith(SIGNUP_SECRET, signupToken);
        assertThat(claims).doesNotContainKey("email").doesNotContainKey("nickname");

        JwtTokenProvider.SignupClaims parsed = provider.parseSignupToken(signupToken);
        assertThat(parsed.providerId()).isEqualTo("pid-1");
        assertThat(parsed.email()).isNull();
        assertThat(parsed.nickname()).isNull();
    }

    @Test
    @DisplayName("signup token의 신원 claim이 그대로 복원된다")
    void signupClaimsRoundTrip() {
        String signupToken = provider.createSignupToken("pid-2", "user@example.com", "홍길동");

        JwtTokenProvider.SignupClaims parsed = provider.parseSignupToken(signupToken);
        assertThat(parsed.providerId()).isEqualTo("pid-2");
        assertThat(parsed.email()).isEqualTo("user@example.com");
        assertThat(parsed.nickname()).isEqualTo("홍길동");
    }

    private Claims parseWith(String secret, String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
