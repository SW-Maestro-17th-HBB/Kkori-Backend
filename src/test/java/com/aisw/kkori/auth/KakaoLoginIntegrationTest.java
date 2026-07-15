package com.aisw.kkori.auth;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KakaoLoginIntegrationTest extends AuthIntegrationTestSupport {

    private static final String LOGIN_URI = "/api/v1/auth/kakao";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("기존 유저는 isNewUser=false와 함께 토큰 쌍을 받는다")
    void existingUserReceivesTokens() throws Exception {
        User user = saveUser("kakao-1001");
        given(kakaoOAuthClient.authenticate(anyString()))
                .willReturn(new KakaoUserInfo("kakao-1001", user.getEmail(), user.getName()));

        postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isNewUser").value(false))
                .andExpect(jsonPath("$.data.isRestored").value(false))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.signupToken").doesNotExist());
    }

    @Test
    @DisplayName("신규 유저는 signupToken만 받고 계정은 생성되지 않는다")
    void newUserReceivesSignupTokenOnly() throws Exception {
        given(kakaoOAuthClient.authenticate(anyString()))
                .willReturn(new KakaoUserInfo("kakao-2002", "new@example.com", "신규"));

        postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(true))
                .andExpect(jsonPath("$.data.signupToken").isNotEmpty())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.isRestored").doesNotExist());

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("유예 내 탈퇴 유저는 계정 변경 없이 isRestored=true와 복구용 signupToken만 받는다")
    void softDeletedUserWithinGraceReceivesRestoreToken() throws Exception {
        User user = saveUser("kakao-3003");
        Instant now = Instant.now();
        user.softDelete(now);
        userRepository.save(user);
        deletionLogRepository.save(DeletionLog.pending(user.getId(), "kakao-3003", now));
        given(kakaoOAuthClient.authenticate(anyString()))
                .willReturn(new KakaoUserInfo("kakao-3003", user.getEmail(), user.getName()));

        postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(false))
                .andExpect(jsonPath("$.data.isRestored").value(true))
                .andExpect(jsonPath("$.data.signupToken").isNotEmpty())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        // 로그인만으로는 복구되지 않는다 — 복구는 재동의 제출 시점에 성립(PRD 기능 4)
        User unchanged = userRepository.findByProviderId("kakao-3003").orElseThrow();
        assertThat(unchanged.isDeleted()).isTrue();
        assertThat(deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(user.getId())
                .orElseThrow().getStatus()).isEqualTo(DeletionStatus.PENDING_PURGE);
    }

    @Test
    @DisplayName("유예 초과 탈퇴 유저는 식별정보가 파기되고 신규 유저로 전환된다")
    void graceExpiredUserIsMaskedAndTreatedAsNew() throws Exception {
        User user = saveUser("kakao-3004");
        Instant past = Instant.now().minus(Duration.ofDays(4));
        user.softDelete(past);
        userRepository.save(user);
        deletionLogRepository.save(DeletionLog.pending(user.getId(), "kakao-3004", past));
        given(kakaoOAuthClient.authenticate(anyString()))
                .willReturn(new KakaoUserInfo("kakao-3004", user.getEmail(), user.getName()));

        postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(true))
                .andExpect(jsonPath("$.data.signupToken").isNotEmpty())
                .andExpect(jsonPath("$.data.isRestored").doesNotExist());

        // 식별정보 선행 파기 — email·name NULL, provider_id 마스킹. 잔여 파기는 배치 몫(로그·스냅샷 유지)
        User purged = userRepository.findById(user.getId()).orElseThrow();
        assertThat(purged.getProviderId()).isEqualTo("PURGED_" + user.getId());
        assertThat(purged.getEmail()).isNull();
        assertThat(purged.getName()).isNull();
        assertThat(purged.isDeleted()).isTrue();
        DeletionLog log = deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(user.getId())
                .orElseThrow();
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PENDING_PURGE);
        assertThat(log.getProviderId()).isEqualTo("kakao-3004");
    }

    @Test
    @DisplayName("파기가 진행·재시도 중(PURGING·FAILED)인 계정의 로그인은 409 PURGE_IN_PROGRESS로 차단된다")
    void purgingOrFailedAccountLoginIsBlocked() throws Exception {
        for (String status : List.of("PURGING", "FAILED")) {
            String providerId = "kakao-3005-" + status;
            User user = saveUser(providerId);
            Instant now = Instant.now();
            user.softDelete(now);
            userRepository.save(user);
            deletionLogRepository.save(DeletionLog.pending(user.getId(), providerId, now));
            jdbcTemplate.update("update deletion_log set status = ? where user_id = ?", status, user.getId());
            given(kakaoOAuthClient.authenticate(anyString()))
                    .willReturn(new KakaoUserInfo(providerId, user.getEmail(), user.getName()));

            postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("U002"));

            // 차단만 하고 아무것도 바꾸지 않는다 — 신규 가입을 허용하면 배치의 unlink와 경합
            assertThat(userRepository.findById(user.getId()).orElseThrow().getProviderId())
                    .isEqualTo(providerId);
        }
    }

    @Test
    @DisplayName("탈퇴 상태인데 deletion_log가 없는 모순 계정은 신규 전환으로 흡수된다")
    void deletedUserWithoutLogIsAbsorbedAsNew() throws Exception {
        User user = saveUser("kakao-3006");
        user.softDelete(Instant.now());
        userRepository.save(user);
        given(kakaoOAuthClient.authenticate(anyString()))
                .willReturn(new KakaoUserInfo("kakao-3006", user.getEmail(), user.getName()));

        postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(true))
                .andExpect(jsonPath("$.data.signupToken").isNotEmpty());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getProviderId())
                .isEqualTo("PURGED_" + user.getId());
    }

    @Test
    @DisplayName("code가 비어 있으면 400 INVALID_CODE(A001)를 반환한다")
    void blankCodeIsRejected() throws Exception {
        postJson(LOGIN_URI, "{\"code\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("A001"));

        postJson(LOGIN_URI, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("A001"));
    }

    @Test
    @DisplayName("만료·재사용 code는 401 KAKAO_AUTH_FAILED(A002)를 반환한다")
    void kakaoAuthFailure() throws Exception {
        given(kakaoOAuthClient.authenticate(anyString()))
                .willThrow(new BusinessException(ErrorCode.KAKAO_AUTH_FAILED));

        postJson(LOGIN_URI, "{\"code\":\"expired-code\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
    }

    @Test
    @DisplayName("카카오 서버 통신 오류는 500 KAKAO_SERVER_ERROR(A003)를 반환한다")
    void kakaoServerFailure() throws Exception {
        given(kakaoOAuthClient.authenticate(anyString()))
                .willThrow(new BusinessException(ErrorCode.KAKAO_SERVER_ERROR));

        postJson(LOGIN_URI, "{\"code\":\"any-code\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("A003"));
    }
}
