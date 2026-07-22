package com.aisw.kkori.user;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.global.logging.LogMasker;
import com.aisw.kkori.global.oauth.KakaoOAuthProperties;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.repository.DeletionLogRepository;
import com.aisw.kkori.user.service.UserService;
import com.aisw.kkori.user.service.WebhookWithdrawalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aisw.kkori.ConcurrencyTestSupport.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카카오 연결 해제 웹훅 통합 테스트 (PRD {@code docs/requirements/user/account.md} 기능 5 검증 기준).
 *
 * <p>모든 요청이 Bearer 토큰 없이 수행된다 — 통과 자체가 permitAll(어드민 키 자체 검증)의 검증이다.
 * 동시성은 기능 3과 같은 이유로 서비스 레벨에서 검증한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class KakaoUnlinkWebhookIntegrationTest extends AuthIntegrationTestSupport {

    private static final String WEBHOOK_URI = "/api/v1/webhook/kakao/unlink";
    private static final String REFERRER = "UNLINK_FROM_APPS";

    @Autowired
    private KakaoOAuthProperties kakaoOAuthProperties;

    @Autowired
    private UserService userService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LogMasker logMasker;

    @MockitoSpyBean
    private WebhookWithdrawalExecutor webhookWithdrawalExecutorSpy;

    @MockitoSpyBean
    private DeletionLogRepository deletionLogRepositorySpy;

    // ── 정상 수신 ────────────────────────────────────────────────

    @Test
    @DisplayName("올바른 어드민 키의 GET 웹훅은 탈퇴 4종 기록을 수행하고 200을 반환한다")
    void getWebhookWithdrawsActiveUser(CapturedOutput output) throws Exception {
        User user = saveUser("kakao-9301");
        seedConsents(user.getId());
        tokenService.issueTokenPair(user.getId());

        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "kakao-9301", REFERRER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value((Object) null));

        // ① soft delete ② RT 전체 폐기 ③ WITHDRAWN append ④ deletion_log — 기능 3과 동일 4종
        User deleted = userRepository.findById(user.getId()).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getUserId().equals(user.getId())))
                .isNotEmpty()
                .allSatisfy(rt -> assertThat(rt.getRevokedAt()).isNotNull());
        assertThat(userConsentRepository.findByUserId(user.getId()).stream()
                .filter(c -> c.getAction() == ConsentAction.WITHDRAWN && c.getConsentType() != ConsentType.MARKETING))
                .hasSize(3);
        List<DeletionLog> logs = deletionLogRepository.findAll().stream()
                .filter(log -> log.getUserId().equals(user.getId()))
                .toList();
        assertThat(logs).hasSize(1);
        DeletionLog log = logs.getFirst();
        assertThat(log.getProviderId()).isEqualTo("kakao-9301");
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PENDING_PURGE);
        // 로그에는 가명값만 — user_id 원문은 어느 경로에서도 남지 않는다 (PRD 기능 5)
        assertThat(output.getOut())
                .contains("referrer_type=" + REFERRER)
                .contains("user_id_hmac=" + logMasker.mask("kakao-9301"))
                .doesNotContain("kakao-9301");
    }

    @Test
    @DisplayName("POST form-urlencoded 웹훅도 GET과 동일하게 탈퇴를 수행한다")
    void postFormWebhookWithdrawsActiveUser() throws Exception {
        User user = saveUser("kakao-9302");

        mockMvc.perform(post(WEBHOOK_URI)
                        .header(HttpHeaders.AUTHORIZATION, validAuth())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("app_id=%s&user_id=kakao-9302&referrer_type=%s"
                                .formatted(kakaoOAuthProperties.appId(), REFERRER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(deletionLogRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("referrer_type 누락은 검증 실패가 아니다 — 로그 전용 필드이므로 탈퇴는 정상 수행된다")
    void missingReferrerTypeStillWithdraws() throws Exception {
        User user = saveUser("kakao-9303");

        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "kakao-9303", null)
                .andExpect(status().isOk());

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
    }

    // ── 검증 실패 — 전부 200 + 상태 무변경 + WARN ─────────────────

    @Test
    @DisplayName("어드민 키·app_id 불일치는 200을 반환하되 유저 무변경 + WARN 로그를 남긴다")
    void mismatchedCredentialsLeaveStateUntouched(CapturedOutput output) throws Exception {
        User user = saveUser("kakao-9304");

        webhookGet("KakaoAK wrong-admin-key", kakaoOAuthProperties.appId(), "kakao-9304", REFERRER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        webhookGet(validAuth(), "999999", "kakao-9304", REFERRER)
                .andExpect(status().isOk());

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(deletionLogRepository.count()).isZero();
        assertThat(output.getOut()).contains("WARN").contains("카카오 웹훅 검증 실패");
        // Authorization 헤더·어드민 키·user_id 원문은 로그에 나타나면 안 된다 (PRD 기능 5)
        assertThat(output.getOut())
                .doesNotContain(kakaoOAuthProperties.adminKey())
                .doesNotContain("KakaoAK")
                .doesNotContain("wrong-admin-key")
                .doesNotContain("kakao-9304")
                .contains("user_id_hmac=" + logMasker.mask("kakao-9304"));
    }

    @Test
    @DisplayName("Authorization·app_id·user_id 누락과 빈 값·비정상 형식도 전부 200 + 상태 무변경이다")
    void missingOrMalformedInputsReturn200WithoutChange() throws Exception {
        User user = saveUser("kakao-9305");

        webhookGet(null, kakaoOAuthProperties.appId(), "kakao-9305", REFERRER).andExpect(status().isOk());
        webhookGet("", kakaoOAuthProperties.appId(), "kakao-9305", REFERRER).andExpect(status().isOk());
        webhookGet("Bearer not-kakao-format", kakaoOAuthProperties.appId(), "kakao-9305", REFERRER)
                .andExpect(status().isOk());
        webhookGet(validAuth(), null, "kakao-9305", REFERRER).andExpect(status().isOk());
        webhookGet(validAuth(), "", "kakao-9305", REFERRER).andExpect(status().isOk());
        webhookGet(validAuth(), kakaoOAuthProperties.appId(), null, REFERRER).andExpect(status().isOk());
        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "", REFERRER).andExpect(status().isOk());
        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "  ", REFERRER).andExpect(status().isOk());

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(deletionLogRepository.count()).isZero();
    }

    // ── 멱등 ────────────────────────────────────────────────────

    @Test
    @DisplayName("미존재·이미 탈퇴 user_id의 웹훅은 200 + 무변경이고 referrer_type이 로그에 남는다")
    void unknownAndWithdrawnUsersAreIdempotent(CapturedOutput output) throws Exception {
        User user = saveUser("kakao-9306");
        userService.withdraw(user.getId());

        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "no-such-user", REFERRER)
                .andExpect(status().isOk());
        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "kakao-9306", REFERRER)
                .andExpect(status().isOk());

        assertThat(deletionLogRepository.count()).isEqualTo(1); // 중복 INSERT 없음
        assertThat(output.getOut())
                .contains("result=NOT_FOUND")
                .contains("result=ALREADY_WITHDRAWN")
                .contains("referrer_type=" + REFERRER)
                // 미존재·이미 탈퇴 경로도 가명값만 기록
                .contains("user_id_hmac=" + logMasker.mask("no-such-user"))
                .contains("user_id_hmac=" + logMasker.mask("kakao-9306"))
                .doesNotContain("no-such-user")
                .doesNotContain("kakao-9306");
    }

    @Test
    @DisplayName("동일 웹훅이 중복 수신되어도 deletion_log는 1건이다")
    void duplicateWebhooksInsertSingleDeletionLog() throws Exception {
        saveUser("kakao-9307");

        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "kakao-9307", REFERRER)
                .andExpect(status().isOk());
        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "kakao-9307", REFERRER)
                .andExpect(status().isOk());

        assertThat(deletionLogRepository.count()).isEqualTo(1);
    }

    // ── 처리 실패 흡수 ───────────────────────────────────────────

    @Test
    @DisplayName("탈퇴 동기화 중 오류는 4종 기록 전부 롤백 후 200을 유지하고 user_id 포함 ERROR 로그를 남긴다")
    void processingErrorIsAbsorbedWithErrorLog(CapturedOutput output) throws Exception {
        User user = saveUser("kakao-9308");
        seedConsents(user.getId());
        tokenService.issueTokenPair(user.getId());
        long consentCountBefore = userConsentRepository.count();
        doThrow(new RuntimeException("deletion_log 저장 실패 주입"))
                .when(deletionLogRepositorySpy).save(any(DeletionLog.class));

        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "kakao-9308", REFERRER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 부분 반영 없이 전부 원상태 — soft delete·RT 폐기·WITHDRAWN append·deletion_log
        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getUserId().equals(user.getId())))
                .isNotEmpty()
                .allSatisfy(rt -> assertThat(rt.getRevokedAt()).isNull());
        assertThat(userConsentRepository.count()).isEqualTo(consentCountBefore);
        assertThat(deletionLogRepository.count()).isZero();
        assertThat(output.getOut()).contains("ERROR")
                .contains("카카오 웹훅 탈퇴 동기화 실패")
                // 수동 조치용 식별자도 가명값 — 원문 특정은 동일 키 DB 대조(PRD 운영 절차)
                .contains("user_id_hmac=" + logMasker.mask("kakao-9308"))
                .doesNotContain("kakao-9308");
    }

    @Test
    @DisplayName("트랜잭션 종료 시점 예외(커밋·timeout)도 오케스트레이터가 흡수해 200을 유지한다")
    void transactionBoundaryExceptionIsAbsorbed(CapturedOutput output) throws Exception {
        saveUser("kakao-9309");
        doThrow(new TransactionSystemException("커밋 실패 주입"))
                .when(webhookWithdrawalExecutorSpy).withdrawIfActive(anyString());

        webhookGet(validAuth(), kakaoOAuthProperties.appId(), "kakao-9309", REFERRER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(output.getOut()).contains("카카오 웹훅 탈퇴 동기화 실패");
    }

    @Test
    @DisplayName("행 잠금 경합으로 트랜잭션이 실제 timeout돼도 잠금 대기 없이 200을 유지한다 — 3초 응답 계약")
    void lockContentionTimeoutIsAbsorbed(CapturedOutput output) throws Exception {
        User user = saveUser("kakao-9312");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 별도 트랜잭션이 user 행 잠금을 점유한 상태에서 웹훅 수신 —
            // withdraw의 조건부 UPDATE가 대기하다 executor timeout(2초)으로 중단돼야 한다
            Future<?> lockHolder = pool.submit(() -> {
                new TransactionTemplate(transactionManager).execute(status -> {
                    userRepository.findWithLockById(user.getId());
                    locked.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
                return null;
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            webhookGet(validAuth(), kakaoOAuthProperties.appId(), "kakao-9312", REFERRER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // 잠금이 아직 안 풀렸는데 200이 반환됨 = timeout이 대기를 끊었다는 증거
            assertThat(output.getOut()).contains("카카오 웹훅 탈퇴 동기화 실패");
            release.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isFalse();
    }

    // ── 동시성 — 탈퇴 트랜잭션 1회 ───────────────────────────────

    @Test
    @DisplayName("웹훅과 DELETE /api/v1/user가 경합해도 탈퇴 트랜잭션은 한 번만 수행된다")
    void concurrentWebhookAndApiWithdrawalRunOnce() throws Exception {
        User user = saveUser("kakao-9310");
        seedConsents(user.getId());

        runConcurrently(
                () -> webhookWithdrawalExecutorSpy.withdrawIfActive("kakao-9310"),
                () -> userService.withdraw(user.getId()));

        assertWithdrawnExactlyOnce(user.getId());
    }

    @Test
    @DisplayName("동일 유저의 웹훅 두 건이 동시에 처리돼도 탈퇴 트랜잭션은 한 번만 수행된다")
    void concurrentDuplicateWebhooksRunOnce() throws Exception {
        User user = saveUser("kakao-9311");
        seedConsents(user.getId());

        runConcurrently(
                () -> webhookWithdrawalExecutorSpy.withdrawIfActive("kakao-9311"),
                () -> webhookWithdrawalExecutorSpy.withdrawIfActive("kakao-9311"));

        assertWithdrawnExactlyOnce(user.getId());
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private String validAuth() {
        return "KakaoAK " + kakaoOAuthProperties.adminKey();
    }

    /** null 파라미터는 요청에서 제외한다 — 누락 케이스 재현용. */
    private ResultActions webhookGet(String authorization, String appId, String userId, String referrerType)
            throws Exception {
        var request = get(WEBHOOK_URI);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (appId != null) {
            request.param("app_id", appId);
        }
        if (userId != null) {
            request.param("user_id", userId);
        }
        if (referrerType != null) {
            request.param("referrer_type", referrerType);
        }
        return mockMvc.perform(request);
    }

    /** 탈퇴 트랜잭션 1회 검증 — deletion_log 1건 + AGREED였던 동의 유형별 WITHDRAWN 각 1건. */
    private void assertWithdrawnExactlyOnce(Long userId) {
        assertThat(userRepository.findById(userId).orElseThrow().isDeleted()).isTrue();
        assertThat(deletionLogRepository.count()).isEqualTo(1);
        List<UserConsent> withdrawn = userConsentRepository.findByUserId(userId).stream()
                .filter(c -> c.getAction() == ConsentAction.WITHDRAWN && c.getConsentType() != ConsentType.MARKETING)
                .toList();
        assertThat(withdrawn).hasSize(3)
                .extracting(UserConsent::getConsentType)
                .containsExactlyInAnyOrder(ConsentType.PRIVACY, ConsentType.AUDIO_USAGE, ConsentType.RESUME_USAGE);
    }

    /** 가입 시 저장되는 동의 이력을 재현한다 — 필수 3종 AGREED, marketing 미동의(WITHDRAWN). */
    private void seedConsents(Long userId) {
        Instant now = Instant.now();
        userConsentRepository.save(UserConsent.create(userId, ConsentType.PRIVACY, true, 1, now));
        userConsentRepository.save(UserConsent.create(userId, ConsentType.AUDIO_USAGE, true, 1, now));
        userConsentRepository.save(UserConsent.create(userId, ConsentType.RESUME_USAGE, true, 1, now));
        userConsentRepository.save(UserConsent.create(userId, ConsentType.MARKETING, false, 1, now));
    }
}
