package com.aisw.kkori.auth;

import com.aisw.kkori.auth.dto.KakaoLoginResponse;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.config.AccountPolicyProperties;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유예 만료 경계 정각 검증 — 고정 Clock 컨텍스트 (PRD account.md 기능 4: 판정식 {@code now < deleted_at + 유예}).
 *
 * <p>과거 시각 세팅 방식으로는 준비~호출 사이에 시간이 흘러 "정각"을 재현할 수 없어,
 * 이 클래스만 {@code Clock}을 고정한다. 현재 시각 기준으로 고정하므로 JWT 발급·auditing에
 * 부작용이 없고, ±1은 timestamptz 정밀도(마이크로초)에 맞춘다.
 */
class GracePeriodBoundaryTest extends AuthIntegrationTestSupport {

    private static final Instant FIXED_NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private AccountPolicyProperties accountPolicyProperties;

    @Test
    @DisplayName("유예 만료 정각(deleted_at + 유예 == now)의 로그인은 복구가 아닌 신규 전환이다")
    void boundaryInstantIsExpired() {
        User user = userDeletedAt("kakao-7101", FIXED_NOW.minus(grace()));

        KakaoLoginResponse response = login(user, "kakao-7101");

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.signupToken()).isNotEmpty();
        // 바인딩 토큰만 발급 — 계정은 변경되지 않는다(파기·신규 생성은 제출 시점)
        assertThat(jwtTokenProvider.parseSignupToken(response.signupToken()).deletionLogId()).isNotNull();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getProviderId())
                .isEqualTo("kakao-7101");
    }

    @Test
    @DisplayName("만료 1µs 전(deleted_at + 유예 == now + 1µs)의 로그인은 복구 대상이다")
    void oneMicrosecondBeforeBoundaryIsWithinGrace() {
        User user = userDeletedAt("kakao-7102", FIXED_NOW.minus(grace()).plus(1, ChronoUnit.MICROS));

        KakaoLoginResponse response = login(user, "kakao-7102");

        assertThat(response.isNewUser()).isFalse();
        assertThat(response.isRestored()).isTrue();
        assertThat(response.signupToken()).isNotEmpty();
        // 복구 토큰만 발급 — 계정은 변경되지 않는다
        User unchanged = userRepository.findById(user.getId()).orElseThrow();
        assertThat(unchanged.isDeleted()).isTrue();
        assertThat(unchanged.getProviderId()).isEqualTo("kakao-7102");
    }

    /** deleted_at·requested_at을 같은 시각으로 세팅한 탈퇴 유저 (PRD 시각 정합 요구와 동일 형태). */
    private User userDeletedAt(String providerId, Instant deletedAt) {
        User user = saveUser(providerId);
        user.softDelete(deletedAt);
        userRepository.save(user);
        deletionLogRepository.save(DeletionLog.pending(user.getId(), providerId, deletedAt));
        return user;
    }

    private KakaoLoginResponse login(User user, String providerId) {
        return tokenService.processKakaoLogin(new KakaoUserInfo(providerId, user.getEmail(), user.getName()));
    }

    private Duration grace() {
        return accountPolicyProperties.withdrawalGracePeriod();
    }
}
