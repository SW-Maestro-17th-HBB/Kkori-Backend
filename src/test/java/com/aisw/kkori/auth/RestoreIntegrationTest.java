package com.aisw.kkori.auth;

import com.aisw.kkori.auth.dto.SignupRequest;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.auth.repository.RefreshTokenRepository;
import com.aisw.kkori.auth.service.AuthService;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.repository.UserConsentRepository;
import com.aisw.kkori.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재동의 기반 계정 복구 통합 테스트 (PRD account.md 기능 4 검증 기준).
 *
 * <p>유예 만료·배치 선점 상태는 {@link JdbcTemplate}로 직접 세팅한다 —
 * {@code requested_at}은 updatable=false, 배치는 미구현이기 때문.
 */
class RestoreIntegrationTest extends AuthIntegrationTestSupport {

    private static final String SIGNUP_URI = "/api/v1/auth/signup";
    private static final String ALL_CONSENTS = """
            [
              {"type": "privacy", "agreed": true},
              {"type": "audio_usage", "agreed": true},
              {"type": "resume_usage", "agreed": true},
              {"type": "marketing", "agreed": false}
            ]""";

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private UserConsentRepository userConsentRepositorySpy;

    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepositorySpy;

    @Test
    @DisplayName("재동의 제출 시 복구가 성립하고, 발급된 AT로 인증 필요 API 호출이 성공한다")
    void restoreSucceedsWithReconsent() throws Exception {
        User user = withdrawnUser("kakao-6001");
        Instant originalCreatedAt = user.getCreatedAt();
        String restoreToken = restoreToken(user);

        ResultActions result = postJson(SIGNUP_URI, signupBody(restoreToken, ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        // 기존 계정 데이터 유지 + deleted_at 해제
        User restored = userRepository.findById(user.getId()).orElseThrow();
        assertThat(restored.isDeleted()).isFalse();
        assertThat(restored.getProviderId()).isEqualTo("kakao-6001");
        assertThat(restored.getEmail()).isEqualTo("kakao-6001@example.com");
        assertThat(restored.getCreatedAt()).isEqualTo(originalCreatedAt);

        // CANCELLED 전환 + 스냅샷 NULL + updated_at 갱신 — 배치 대상(PENDING_PURGE·FAILED)에서 제외
        DeletionLog log = deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(user.getId())
                .orElseThrow();
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.CANCELLED);
        assertThat(log.getProviderId()).isNull();
        assertThat(log.getUpdatedAt()).isAfter(log.getRequestedAt());

        // 제출 동의가 최신 버전(1 고정)으로 append — 필수 3종 AGREED + marketing WITHDRAWN
        List<UserConsent> consents = userConsentRepository.findByUserId(user.getId());
        assertThat(consents).hasSize(4)
                .allSatisfy(c -> assertThat(c.getVersion()).isEqualTo(1));
        assertThat(consents.stream().filter(c -> c.getAction() == ConsentAction.AGREED)).hasSize(3);

        // 복구 AT로 인증 필요 API 호출 성공 (PRD 검증 기준)
        String accessToken = responseData(result).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/user").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId()));
    }

    @Test
    @DisplayName("복구 제출에서 필수 동의가 빠지면 400이고 계정은 탈퇴 상태를 유지한다")
    void missingRequiredConsentKeepsAccountDeleted() throws Exception {
        User user = withdrawnUser("kakao-6002");
        String restoreToken = restoreToken(user);
        String withoutResumeUsage = """
                [
                  {"type": "privacy", "agreed": true},
                  {"type": "audio_usage", "agreed": true}
                ]""";

        postJson(SIGNUP_URI, signupBody(restoreToken, withoutResumeUsage))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("A004"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(activeLogStatus(user)).isEqualTo(DeletionStatus.PENDING_PURGE);
        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("복구 트랜잭션 중단 시 deleted_at 해제·CANCELLED 전환·동의 append가 전부 롤백된다")
    void restoreRollsBackAllWritesOnFailure() throws Exception {
        User user = withdrawnUser("kakao-6003");
        String restoreToken = restoreToken(user);
        willThrow(new RuntimeException("동의 저장 실패 주입"))
                .given(userConsentRepositorySpy).save(any(UserConsent.class));

        postJson(SIGNUP_URI, signupBody(restoreToken, ALL_CONSENTS))
                .andExpect(status().isInternalServerError());

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(activeLogStatus(user)).isEqualTo(DeletionStatus.PENDING_PURGE);
        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("제출 시점에 유예가 만료됐으면 식별정보를 파기하고 409 없이 신규 계정을 생성한다")
    void expiredAtSubmitCreatesNewAccount() throws Exception {
        User user = withdrawnUser("kakao-6004");
        String restoreToken = restoreToken(user);
        backdateWithdrawal(user, Duration.ofDays(4));

        postJson(SIGNUP_URI, signupBody(restoreToken, ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        // 기존 계정은 식별정보 선행 파기(마스킹), 로그는 배치 몫으로 PENDING_PURGE·스냅샷 유지
        User purged = userRepository.findById(user.getId()).orElseThrow();
        assertThat(purged.getProviderId()).isEqualTo("PURGED_" + user.getId());
        assertThat(purged.getEmail()).isNull();
        assertThat(purged.isDeleted()).isTrue();
        assertThat(activeLogStatus(user)).isEqualTo(DeletionStatus.PENDING_PURGE);
        assertThat(deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(user.getId())
                .orElseThrow().getProviderId()).isEqualTo("kakao-6004");

        // 신규 계정이 실제 provider_id로 생성됨 (UNIQUE 충돌 없음)
        User fresh = userRepository.findByProviderId("kakao-6004").orElseThrow();
        assertThat(fresh.getId()).isNotEqualTo(user.getId());
        assertThat(fresh.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("유예 만료 경로 중단 시 기존 계정 마스킹과 신규 생성이 함께 롤백된다 (한 트랜잭션)")
    void expiredPathRollsBackAtomically() throws Exception {
        User user = withdrawnUser("kakao-6005");
        String restoreToken = restoreToken(user);
        backdateWithdrawal(user, Duration.ofDays(4));
        willThrow(new RuntimeException("RT 저장 실패 주입"))
                .given(refreshTokenRepositorySpy).save(any());

        postJson(SIGNUP_URI, signupBody(restoreToken, ALL_CONSENTS))
                .andExpect(status().isInternalServerError());

        // 마스킹 롤백 — provider_id 원복, 신규 계정 미생성
        assertThat(userRepository.findById(user.getId()).orElseThrow().getProviderId()).isEqualTo("kakao-6005");
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("복구에 사용된 토큰은 재탈퇴 후 재사용해도 401로 거부된다 — deletion_log_id 바인딩")
    void usedRestoreTokenCannotUndoLaterWithdrawal() throws Exception {
        User user = withdrawnUser("kakao-6006");
        String restoreToken = restoreToken(user);
        postJson(SIGNUP_URI, signupBody(restoreToken, ALL_CONSENTS)).andExpect(status().isCreated());
        userService.withdraw(user.getId()); // 재탈퇴 — 새 deletion_log 생성

        postJson(SIGNUP_URI, signupBody(restoreToken, ALL_CONSENTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A005"));

        // 최신 탈퇴가 되돌려지지 않는다
        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(activeLogStatus(user)).isEqualTo(DeletionStatus.PENDING_PURGE);
    }

    @Test
    @DisplayName("신규 가입용 토큰으로는 탈퇴 계정을 복구할 수 없다 — 401로 재로그인 유도")
    void plainSignupTokenCannotRestoreDeletedAccount() throws Exception {
        User user = withdrawnUser("kakao-6007");
        String plainToken = jwtTokenProvider.createSignupToken("kakao-6007", null, null);

        postJson(SIGNUP_URI, signupBody(plainToken, ALL_CONSENTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A005"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("파기 배치가 선점한(PURGING) 건의 복구 제출은 409 PURGE_IN_PROGRESS로 차단된다")
    void purgingClaimedLogRejectsRestore() throws Exception {
        User user = withdrawnUser("kakao-6008");
        String restoreToken = restoreToken(user);
        jdbcTemplate.update("update deletion_log set status = 'PURGING' where user_id = ?", user.getId());

        postJson(SIGNUP_URI, signupBody(restoreToken, ALL_CONSENTS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("U002"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("동일 복구 토큰의 동시 제출은 한 건만 복구를 수행한다")
    void concurrentRestoreSubmissionsOnlyOneSucceeds() throws Exception {
        User user = withdrawnUser("kakao-6009");
        SignupRequest request = new SignupRequest(restoreToken(user), List.of(
                new SignupRequest.ConsentItem(ConsentType.PRIVACY, true),
                new SignupRequest.ConsentItem(ConsentType.AUDIO_USAGE, true),
                new SignupRequest.ConsentItem(ConsentType.RESUME_USAGE, true),
                new SignupRequest.ConsentItem(ConsentType.MARKETING, false)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int succeeded = 0;
        int rejected = 0;
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<TokenResponse>> futures = List.of(
                    pool.submit(() -> { start.await(); return authService.signup(request); }),
                    pool.submit(() -> { start.await(); return authService.signup(request); }));
            start.countDown();
            for (Future<TokenResponse> future : futures) {
                try {
                    assertThat(future.get()).isNotNull();
                    succeeded++;
                } catch (Exception e) {
                    assertThat(e.getCause()).isInstanceOf(BusinessException.class)
                            .extracting(cause -> ((BusinessException) cause).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_SIGNUP_TOKEN);
                    rejected++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(userConsentRepository.findByUserId(user.getId())).hasSize(4); // 동의 1세트만 append
    }

    @Test
    @DisplayName("만료 로그인 → 배치 PURGING 선점 → 제출 순서에서 제출이 409로 차단되고 계정이 변경되지 않는다")
    void expiredLoginTokenSubmitBlockedAfterPurgeClaim() throws Exception {
        User user = withdrawnUser("kakao-6012");
        backdateWithdrawal(user, Duration.ofDays(4));
        // 만료 로그인 — 계정 변경 없이 바인딩 토큰만 발급된다
        String boundToken = tokenService.processKakaoLogin(
                new KakaoUserInfo("kakao-6012", user.getEmail(), user.getName())).signupToken();
        // 로그인과 제출 사이에 배치가 선점
        jdbcTemplate.update("update deletion_log set status = 'PURGING' where user_id = ?", user.getId());

        postJson(SIGNUP_URI, signupBody(boundToken, ALL_CONSENTS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("U002"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getProviderId())
                .isEqualTo("kakao-6012"); // 마스킹되지 않는다
        assertThat(userRepository.count()).isEqualTo(1); // 신규 계정 미생성
    }

    @Test
    @DisplayName("유예 만료 로그인과 배치의 PURGING 선점이 경합해도 선점이 커밋되면 409로 차단되고 마스킹되지 않는다")
    void expiredLoginBlockedByConcurrentPurgeClaim() throws Exception {
        User user = withdrawnUser("kakao-6010");
        backdateWithdrawal(user, Duration.ofDays(4));

        BusinessException thrown = runWhileLogClaimed(user, () ->
                tokenService.processKakaoLogin(new KakaoUserInfo("kakao-6010", user.getEmail(), user.getName())));

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.PURGE_IN_PROGRESS);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getProviderId())
                .isEqualTo("kakao-6010"); // 식별정보 파기가 진행되지 않는다
    }

    @Test
    @DisplayName("유예 만료 복구 제출과 배치의 PURGING 선점이 경합해도 선점이 커밋되면 409로 차단되고 신규 계정이 생기지 않는다")
    void expiredSubmitBlockedByConcurrentPurgeClaim() throws Exception {
        User user = withdrawnUser("kakao-6011");
        SignupRequest request = new SignupRequest(restoreToken(user), List.of(
                new SignupRequest.ConsentItem(ConsentType.PRIVACY, true),
                new SignupRequest.ConsentItem(ConsentType.AUDIO_USAGE, true),
                new SignupRequest.ConsentItem(ConsentType.RESUME_USAGE, true)));
        backdateWithdrawal(user, Duration.ofDays(4));

        BusinessException thrown = runWhileLogClaimed(user, () -> authService.signup(request));

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.PURGE_IN_PROGRESS);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getProviderId())
                .isEqualTo("kakao-6011");
        assertThat(userRepository.count()).isEqualTo(1); // 신규 계정 미생성
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    /**
     * 배치의 PURGING 선점을 미커밋 트랜잭션으로 쥔 채 action을 실행한다 —
     * action은 deletion_log 행 잠금에서 대기하다, 선점 커밋 후 잠금 하 재확인으로
     * PURGING을 보고 실패해야 한다. 던져진 BusinessException을 반환한다.
     */
    private BusinessException runWhileLogClaimed(User user, Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch claimed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> claimer = pool.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    jdbcTemplate.update(
                            "update deletion_log set status = 'PURGING' where user_id = ? and status = 'PENDING_PURGE'",
                            user.getId());
                    claimed.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            });
            claimed.await();
            Future<BusinessException> actionResult = pool.submit(() -> {
                try {
                    action.run();
                    return null;
                } catch (BusinessException e) {
                    return e;
                }
            });
            Thread.sleep(300); // action이 로그 행 잠금 대기에 진입할 시간
            release.countDown();
            claimer.get();
            BusinessException thrown = actionResult.get();
            assertThat(thrown).as("선점된 로그에 대한 판정은 실패해야 한다").isNotNull();
            return thrown;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 탈퇴 상태의 유저를 실제 흐름(withdraw)으로 준비한다 — deletion_log·RT 폐기 포함. */
    private User withdrawnUser(String providerId) {
        User user = saveUser(providerId);
        userService.withdraw(user.getId());
        return user;
    }

    /** 실제 판정 흐름(processKakaoLogin)으로 복구용 signup token을 얻는다. */
    private String restoreToken(User user) {
        return tokenService.processKakaoLogin(
                new KakaoUserInfo(user.getProviderId(), user.getEmail(), user.getName())).signupToken();
    }

    /** 유예 만료 재현 — requested_at은 updatable=false라 JDBC로 직접 과거 세팅한다. */
    private void backdateWithdrawal(User user, Duration age) {
        Timestamp past = Timestamp.from(Instant.now().minus(age));
        jdbcTemplate.update("update users set deleted_at = ? where id = ?", past, user.getId());
        jdbcTemplate.update("update deletion_log set requested_at = ? where user_id = ?", past, user.getId());
    }

    private DeletionStatus activeLogStatus(User user) {
        return deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(user.getId())
                .orElseThrow().getStatus();
    }

    private String signupBody(String signupToken, String consents) {
        return "{\"signupToken\":\"%s\",\"consents\":%s}".formatted(signupToken, consents);
    }
}
