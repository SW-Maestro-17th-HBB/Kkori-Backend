package com.aisw.kkori.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "test-jwt-secret-key-for-unit-tests-32bytes!";
    private static final String SIGNUP_SECRET = "test-signup-secret-key-for-unit-tests-32b!";

    private final JwtProperties properties = new JwtProperties(
            SECRET, SIGNUP_SECRET, Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(10));
    private final JwtTokenProvider provider = new JwtTokenProvider(properties);

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
        JwtTokenProvider expiredProvider = new JwtTokenProvider(new JwtProperties(
                SECRET, SIGNUP_SECRET, Duration.ofSeconds(-10), Duration.ofDays(14), Duration.ofMinutes(10)));
        String expired = expiredProvider.createAccessToken(1L);

        assertThatThrownBy(() -> provider.parseAccessToken(expired))
                .isInstanceOf(JwtException.class);
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
