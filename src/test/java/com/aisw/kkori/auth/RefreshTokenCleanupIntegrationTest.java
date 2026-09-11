package com.aisw.kkori.auth;

import com.aisw.kkori.auth.domain.RefreshToken;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.auth.repositoryservice.AuthRepositoryService;
import com.aisw.kkori.auth.service.RefreshTokenCleanupScheduler;
import com.aisw.kkori.auth.service.RefreshTokenCleanupService;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.jwt.JwtProperties;
import com.aisw.kkori.global.jwt.TokenHasher;
import com.aisw.kkori.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RT 청소 배치 (PRD {@code docs/requirements/user/deletion.md} 기능 6 검증 기준) — 고정 Clock으로 경계를 재현한다.
 */
class RefreshTokenCleanupIntegrationTest extends AuthIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(2);

    @Autowired
    private AuthRepositoryService authRepositoryService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RefreshTokenCleanupService serviceAt(Instant now, Duration retention) {
        JwtProperties props = new JwtProperties(jwtProperties.secret(), jwtProperties.signupSecret(),
                jwtProperties.accessTokenTtl(), jwtProperties.refreshTokenTtl(), jwtProperties.signupTokenTtl(),
                jwtProperties.refreshTokenCleanupInterval(), retention);
        return new RefreshTokenCleanupService(authRepositoryService, props, transactionTemplate,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /** 저장 행 시딩 — 만료·폐기 시각을 직접 지정한다(재발급 경로와 무관한 청소 조건 검증). */
    private String token(long userId, String hash, Instant expiredAt, Instant revokedAt) {
        RefreshToken saved = refreshTokenRepository.save(RefreshToken.issue(
                userId, hash, "jti-" + hash, expiredAt.minus(Duration.ofDays(14)), expiredAt));
        if (revokedAt != null) {
            jdbcTemplate.update("update refresh_token set revoked_at = ? where id = ?",
                    Timestamp.from(revokedAt), saved.getId());
        }
        return hash;
    }

    private boolean exists(String hash) {
        return refreshTokenRepository.findByTokenHash(hash).isPresent();
    }

    @ParameterizedTest(name = "expired_at = now {0}초 → 삭제 {1}")
    @CsvSource({"0, true", "1, false"})
    @DisplayName("만료된 RT는 즉시 삭제되고(경계 정각 포함) 만료 전 RT는 남는다")
    void deletesExpiredTokens(long offsetSeconds, boolean deleted) {
        User user = saveUser("kakao-rtc-1-" + offsetSeconds);
        String hash = token(user.getId(), "expired-" + offsetSeconds, NOW.plusSeconds(offsetSeconds), null);

        serviceAt(NOW, RETENTION).runCycle();

        assertThat(exists(hash)).isEqualTo(!deleted);
    }

    @ParameterizedTest(name = "revoked_at = now - retention {0}초 → 삭제 {1}")
    @CsvSource({"0, true", "-1, false"})
    @DisplayName("폐기 후 보존 기간이 지난 RT는 삭제되고(경계 정각 포함) 보존 기간 내 폐기 RT는 남는다")
    void deletesRevokedTokensAfterRetention(long offsetSeconds, boolean deleted) {
        User user = saveUser("kakao-rtc-2-" + Math.abs(offsetSeconds));
        Instant revokedAt = NOW.minus(RETENTION).minusSeconds(offsetSeconds);
        String hash = token(user.getId(), "revoked-" + offsetSeconds, NOW.plus(Duration.ofDays(10)), revokedAt);

        serviceAt(NOW, RETENTION).runCycle();

        assertThat(exists(hash)).isEqualTo(!deleted);
    }

    @Test
    @DisplayName("유효 RT(미만료·미폐기)는 삭제되지 않는다")
    void keepsValidTokens() {
        User user = saveUser("kakao-rtc-3");
        String hash = token(user.getId(), "valid", NOW.plus(Duration.ofDays(10)), null);

        serviceAt(NOW, RETENTION).runCycle();

        assertThat(exists(hash)).isTrue();
    }

    @Test
    @DisplayName("회전 체인의 선행 토큰이 청소되어도 후속 토큰 재발급은 정상이고, 삭제된 선행 토큰 재사용은 RT_NOT_FOUND다")
    void rotationChainSurvivesCleanupAndDeletedTokenReuseIsNotFound() {
        User user = saveUser("kakao-rtc-4");
        TokenResponse first = tokenService.issueTokenPair(user.getId());
        TokenResponse second = tokenService.reissue(first.refreshToken()); // first 폐기 + replaced_by = second
        String firstHash = TokenHasher.sha256Hex(first.refreshToken());
        jdbcTemplate.update("update refresh_token set revoked_at = ? where token_hash = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(3))), firstHash);

        serviceAt(Instant.now().truncatedTo(ChronoUnit.MICROS), RETENTION).runCycle();

        assertThat(exists(firstHash)).isFalse();
        assertThat(exists(TokenHasher.sha256Hex(second.refreshToken()))).isTrue();
        assertThatThrownBy(() -> tokenService.reissue(first.refreshToken()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RT_NOT_FOUND));
        TokenResponse third = tokenService.reissue(second.refreshToken());
        assertThat(third.refreshToken()).isNotEqualTo(second.refreshToken());
    }

    @Test
    @DisplayName("보존 기간 설정 변경이 반영된다 — 같은 폐기 RT가 5일 설정에서는 남고 1일 설정에서는 삭제된다")
    void retentionSettingOverride() {
        User user = saveUser("kakao-rtc-5");
        String hash = token(user.getId(), "revoked-2d", NOW.plus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(2)));

        serviceAt(NOW, Duration.ofDays(5)).runCycle();
        assertThat(exists(hash)).isTrue();

        serviceAt(NOW, Duration.ofDays(1)).runCycle();
        assertThat(exists(hash)).isFalse();
    }

    @Test
    @DisplayName("스케줄러는 jwt.refresh-token-cleanup-interval을 fixedDelay로 쓴다")
    void schedulerIsWiredToConfiguredInterval() throws Exception {
        Scheduled scheduled = RefreshTokenCleanupScheduler.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${jwt.refresh-token-cleanup-interval}");
    }
}
