package com.aisw.kkori.user;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.config.AccountPolicyProperties;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.dto.WithdrawResponse;
import com.aisw.kkori.user.repository.DeletionLogRepository;
import com.aisw.kkori.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계정 조회·수정·탈퇴 통합 테스트 (PRD {@code docs/requirements/user/account.md} 기능 1~3 검증 기준).
 *
 * <p>동시성·멱등은 서비스 레벨로 검증한다 — HTTP 재호출은 JWT 필터가 탈퇴 유저를
 * 401로 끊어 조건부 UPDATE 경합에 도달하지 못하기 때문(잔여 AT 차단은 별도 HTTP 테스트가 담당).
 */
class UserAccountIntegrationTest extends AuthIntegrationTestSupport {

    private static final String USER_URI = "/api/v1/user";

    @Autowired
    private UserService userService;

    @Autowired
    private AccountPolicyProperties accountPolicyProperties;

    @MockitoSpyBean
    private DeletionLogRepository deletionLogRepositorySpy;

    // ── 내 정보 조회 ──────────────────────────────────────────────

    @Test
    @DisplayName("내 정보 조회는 id·email·name·createdAt을 반환하고 provider_id는 노출하지 않는다")
    void getMyInfoReturnsAccountFields() throws Exception {
        User user = saveUser("kakao-9001");
        String accessToken = issueAccessToken(user);

        ResultActions result = getWithBearer(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId()))
                .andExpect(jsonPath("$.data.email").value(user.getEmail()))
                .andExpect(jsonPath("$.data.name").value("테스터"))
                .andExpect(jsonPath("$.data.providerId").doesNotExist())
                .andExpect(jsonPath("$.data.provider_id").doesNotExist());

        JsonNode data = responseData(result);
        assertThat(data.get("createdAt").asText()).endsWith("Z"); // ISO-8601 UTC 직렬화
    }

    @Test
    @DisplayName("이메일 미제공 카카오 계정은 email이 null로 반환된다")
    void getMyInfoReturnsNullEmail() throws Exception {
        User user = userRepository.save(User.create("kakao-9002", null, null));
        String accessToken = issueAccessToken(user);

        getWithBearer(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value((Object) null))
                .andExpect(jsonPath("$.data.name").value((Object) null));
    }

    @Test
    @DisplayName("토큰 없이 내 정보 조회 시 401이 반환된다")
    void getMyInfoWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get(USER_URI))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    // ── 내 정보 수정 ──────────────────────────────────────────────

    @Test
    @DisplayName("name 수정 시 앞뒤 공백이 제거되어 저장되고 수정된 정보가 반환된다")
    void updateNameTrimsAndPersists() throws Exception {
        User user = saveUser("kakao-9101");
        String accessToken = issueAccessToken(user);

        patchNameWithBearer("  새 이름  ", accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 이름"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getName()).isEqualTo("새 이름");
    }

    @Test
    @DisplayName("경계값 1자·100자 name은 허용된다")
    void updateNameAcceptsBoundaryLengths() throws Exception {
        User user = saveUser("kakao-9102");
        String accessToken = issueAccessToken(user);

        patchNameWithBearer("a", accessToken).andExpect(status().isOk());
        patchNameWithBearer("가".repeat(100), accessToken).andExpect(status().isOk());
    }

    @Test
    @DisplayName("BMP 밖 이모지 100자는 코드 포인트 기준으로 허용된다 — UTF-16 단위로는 200")
    void updateNameCountsCodePoints() throws Exception {
        User user = saveUser("kakao-9103");
        String accessToken = issueAccessToken(user);
        String emojiName = "😀".repeat(100);
        assertThat(emojiName.length()).isEqualTo(200); // 서로게이트 쌍 — length()로 세면 거부됐을 값

        patchNameWithBearer(emojiName, accessToken).andExpect(status().isOk());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getName()).isEqualTo(emojiName);
    }

    @Test
    @DisplayName("name 누락·null·공백뿐·101자는 U001로 거부되고 DB가 변경되지 않는다")
    void updateNameRejectsInvalidValues() throws Exception {
        User user = saveUser("kakao-9104");
        String accessToken = issueAccessToken(user);

        patchJsonWithBearer("{}", accessToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("U001"));
        patchJsonWithBearer("{\"name\":null}", accessToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("U001"));
        patchNameWithBearer("   ", accessToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("U001"));
        patchNameWithBearer("가".repeat(101), accessToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("U001"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getName()).isEqualTo("테스터");
    }

    @Test
    @DisplayName("미지원 필드(email)는 무시되고 name만 반영된다")
    void updateIgnoresUnsupportedFields() throws Exception {
        User user = saveUser("kakao-9105");
        String accessToken = issueAccessToken(user);

        patchJsonWithBearer("{\"name\":\"새 이름\",\"email\":\"hack@example.com\"}", accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 이름"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail())
                .isEqualTo("kakao-9105@example.com");
    }

    @Test
    @DisplayName("토큰 없이 수정 시 401이 반환된다")
    void updateWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(patch(USER_URI).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    // ── 회원 탈퇴 ────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 없이 탈퇴 요청 시 401이 반환된다")
    void withdrawWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(delete(USER_URI))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("탈퇴는 soft delete·RT 전체 폐기·WITHDRAWN append·deletion_log 등록을 한 트랜잭션 시각으로 수행한다")
    void withdrawPerformsAllFourWritesWithSameInstant() throws Exception {
        User user = saveUser("kakao-9201");
        seedConsents(user.getId());
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        ResultActions result = deleteWithBearer(tokens.accessToken()).andExpect(status().isOk());
        Instant purgeScheduledAt = Instant.parse(responseData(result).get("purgeScheduledAt").asText());

        // ① soft delete
        User deleted = userRepository.findById(user.getId()).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        // ② RT 전체 폐기
        assertThat(refreshTokenRepository.findAll())
                .allSatisfy(rt -> assertThat(rt.getRevokedAt()).isNotNull());
        // ③ 탈퇴 시점 AGREED였던 3종에만 WITHDRAWN append — 원래 미동의(marketing)는 중복 append 없음
        List<UserConsent> consents = userConsentRepository.findByUserId(user.getId());
        List<UserConsent> appended = consents.stream()
                .filter(c -> c.getCreatedAt().equals(deleted.getDeletedAt()))
                .toList();
        assertThat(appended).hasSize(3)
                .allSatisfy(c -> assertThat(c.getAction()).isEqualTo(ConsentAction.WITHDRAWN))
                .extracting(UserConsent::getConsentType)
                .containsExactlyInAnyOrder(ConsentType.PRIVACY, ConsentType.AUDIO_USAGE, ConsentType.RESUME_USAGE);
        assertThat(consents.stream()
                .filter(c -> c.getConsentType() == ConsentType.MARKETING && c.getAction() == ConsentAction.WITHDRAWN))
                .hasSize(1);
        // ④ deletion_log — provider_id 스냅샷·상태·시각 동일성(deleted_at == requested_at == WITHDRAWN.created_at)
        DeletionLog log = deletionLogRepository.findAll().getFirst();
        assertThat(log.getUserId()).isEqualTo(user.getId());
        assertThat(log.getProviderId()).isEqualTo("kakao-9201");
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PENDING_PURGE);
        assertThat(log.getRequestedAt()).isEqualTo(deleted.getDeletedAt());
        assertThat(log.getUpdatedAt()).isEqualTo(log.getRequestedAt());
        assertThat(log.getPurgedAt()).isNull();
        // 응답 purgeScheduledAt = deleted_at + 유예 기간(3일)
        assertThat(micros(purgeScheduledAt))
                .isEqualTo(micros(deleted.getDeletedAt().plus(accountPolicyProperties.withdrawalGracePeriod())));
    }

    @Test
    @DisplayName("탈퇴 직후 잔여 AT는 매 요청 deleted_at 검증으로 401이 된다")
    void residualAccessTokenIsRejectedAfterWithdrawal() throws Exception {
        User user = saveUser("kakao-9202");
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        deleteWithBearer(tokens.accessToken()).andExpect(status().isOk());

        getWithBearer(tokens.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("탈퇴로 폐기된 RT는 Grace Period 없이 재발급이 거부된다")
    void revokedRefreshTokenCannotReissueAfterWithdrawal() throws Exception {
        User user = saveUser("kakao-9203");
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        deleteWithBearer(tokens.accessToken()).andExpect(status().isOk());

        postJson("/api/v1/auth/reissue", "{\"refreshToken\":\"%s\"}".formatted(tokens.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A007"));
    }

    @Test
    @DisplayName("이미 탈퇴된 유저의 서비스 재호출은 기존 deleted_at 기준으로 멱등 응답한다")
    void withdrawIsIdempotentOnServiceLevel() {
        User user = saveUser("kakao-9204");
        seedConsents(user.getId());

        WithdrawResponse first = userService.withdraw(user.getId());
        WithdrawResponse second = userService.withdraw(user.getId());

        assertThat(micros(second.purgeScheduledAt())).isEqualTo(micros(first.purgeScheduledAt()));
        assertThat(deletionLogRepository.count()).isEqualTo(1);
        assertThat(userConsentRepository.findByUserId(user.getId()).stream()
                .filter(c -> c.getAction() == ConsentAction.WITHDRAWN && c.getConsentType() == ConsentType.PRIVACY))
                .hasSize(1);
    }

    @Test
    @DisplayName("동일 유저의 동시 탈퇴 2건 중 상태 전이를 수행한 한 건만 후속 작업을 진행한다")
    void concurrentWithdrawalsAreSerialized() throws Exception {
        User user = saveUser("kakao-9205");
        seedConsents(user.getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<WithdrawResponse>> futures = List.of(
                    pool.submit(() -> awaitAndWithdraw(start, user.getId())),
                    pool.submit(() -> awaitAndWithdraw(start, user.getId())));
            start.countDown();

            WithdrawResponse first = futures.get(0).get();
            WithdrawResponse second = futures.get(1).get();
            assertThat(micros(first.purgeScheduledAt())).isEqualTo(micros(second.purgeScheduledAt()));
        } finally {
            pool.shutdownNow();
        }

        assertThat(deletionLogRepository.count()).isEqualTo(1);
        assertThat(userConsentRepository.findByUserId(user.getId()).stream()
                .filter(c -> c.getAction() == ConsentAction.WITHDRAWN && c.getConsentType() != ConsentType.MARKETING))
                .hasSize(3);
    }

    @Test
    @DisplayName("탈퇴된 유저의 수정 요청은 잠금 후 재확인으로 401이 된다")
    void updateNameOnWithdrawnUserIsRejected() {
        User user = saveUser("kakao-9207");
        userService.withdraw(user.getId());

        assertThatThrownBy(() -> userService.updateName(user.getId(), "되살리기 시도"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("수정과 탈퇴가 동시에 실행돼도 계정이 되살아나지 않는다 — user 행 잠금 직렬화")
    void concurrentUpdateAndWithdrawNeverResurrects() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                User user = saveUser("kakao-9208-" + i);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> update = pool.submit(() -> {
                    start.await();
                    try {
                        userService.updateName(user.getId(), "경합 수정");
                    } catch (BusinessException e) {
                        // 탈퇴가 먼저 커밋된 경우 401 — 정상 경로
                    }
                    return null;
                });
                Future<?> withdraw = pool.submit(() -> {
                    start.await();
                    userService.withdraw(user.getId());
                    return null;
                });
                start.countDown();
                update.get();
                withdraw.get();

                assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted())
                        .as("반복 %d — 수정 flush가 탈퇴를 되덮으면 안 된다", i)
                        .isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("재발급과 탈퇴가 동시에 실행돼도 탈퇴 완료 후 활성 RT가 남지 않는다 — 잠금 순서 user → RT")
    void concurrentReissueAndWithdrawLeavesNoActiveRefreshToken() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                User user = saveUser("kakao-9209-" + i);
                TokenResponse tokens = tokenService.issueTokenPair(user.getId());
                CountDownLatch start = new CountDownLatch(1);
                Future<?> reissue = pool.submit(() -> {
                    start.await();
                    try {
                        tokenService.reissue(tokens.refreshToken());
                    } catch (BusinessException e) {
                        // 탈퇴가 먼저면 A007 — 정상 경로
                    }
                    return null;
                });
                Future<?> withdraw = pool.submit(() -> {
                    start.await();
                    userService.withdraw(user.getId());
                    return null;
                });
                start.countDown();
                reissue.get();
                withdraw.get();

                assertThat(refreshTokenRepository.findAll().stream()
                        .filter(rt -> rt.getUserId().equals(user.getId()))
                        .filter(rt -> rt.getRevokedAt() == null))
                        .as("반복 %d — 재발급이 INSERT한 새 RT까지 폐기돼야 한다", i)
                        .isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("카카오 로그인과 탈퇴가 동시에 실행돼도 최종 탈퇴 상태면 활성 RT가 남지 않는다")
    void concurrentKakaoLoginAndWithdrawKeepsInvariant() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                String providerId = "kakao-9210-" + i;
                User user = saveUser(providerId);
                KakaoUserInfo info = new KakaoUserInfo(providerId, user.getEmail(), user.getName());
                CountDownLatch start = new CountDownLatch(1);
                Future<?> login = pool.submit(() -> {
                    start.await();
                    tokenService.processKakaoLogin(info);
                    return null;
                });
                Future<?> withdraw = pool.submit(() -> {
                    start.await();
                    userService.withdraw(user.getId());
                    return null;
                });
                start.countDown();
                login.get();
                withdraw.get();

                // 로그인만으로는 복구되지 않으므로(재동의 필요 — HBB1-245) 최종 상태는 항상 탈퇴이고,
                // 로그인이 먼저 발급한 RT도 탈퇴의 전량 폐기에 잡혀야 한다
                assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted())
                        .as("반복 %d — 로그인이 탈퇴를 되돌리면 안 된다", i)
                        .isTrue();
                assertThat(refreshTokenRepository.findAll().stream()
                        .filter(rt -> rt.getUserId().equals(user.getId()))
                        .filter(rt -> rt.getRevokedAt() == null))
                        .as("반복 %d — 탈퇴 상태인데 로그인 발급 RT가 살아 있으면 안 된다", i)
                        .isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("탈퇴 트랜잭션 중단 시 soft delete·RT 폐기·WITHDRAWN·deletion_log가 전부 롤백된다")
    void withdrawalRollsBackAllWritesOnFailure() throws Exception {
        User user = saveUser("kakao-9206");
        seedConsents(user.getId());
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());
        long consentCountBefore = userConsentRepository.count();
        doThrow(new RuntimeException("deletion_log 저장 실패 주입"))
                .when(deletionLogRepositorySpy).save(any(DeletionLog.class));

        deleteWithBearer(tokens.accessToken())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("C001"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(refreshTokenRepository.findAll())
                .allSatisfy(rt -> assertThat(rt.getRevokedAt()).isNull());
        assertThat(userConsentRepository.count()).isEqualTo(consentCountBefore);
        assertThat(deletionLogRepository.count()).isZero();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private WithdrawResponse awaitAndWithdraw(CountDownLatch start, Long userId) throws InterruptedException {
        start.await();
        return userService.withdraw(userId);
    }

    private String issueAccessToken(User user) {
        return tokenService.issueTokenPair(user.getId()).accessToken();
    }

    /** 가입 시 저장되는 동의 이력을 재현한다 — 필수 3종 AGREED, marketing 미동의(WITHDRAWN). */
    private void seedConsents(Long userId) {
        Instant now = Instant.now();
        userConsentRepository.save(UserConsent.create(userId, ConsentType.PRIVACY, true, 1, now));
        userConsentRepository.save(UserConsent.create(userId, ConsentType.AUDIO_USAGE, true, 1, now));
        userConsentRepository.save(UserConsent.create(userId, ConsentType.RESUME_USAGE, true, 1, now));
        userConsentRepository.save(UserConsent.create(userId, ConsentType.MARKETING, false, 1, now));
    }

    private ResultActions getWithBearer(String bearerToken) throws Exception {
        return mockMvc.perform(get(USER_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken));
    }

    private ResultActions patchNameWithBearer(String name, String bearerToken) throws Exception {
        return patchJsonWithBearer("{\"name\":%s}".formatted(objectMapper.writeValueAsString(name)), bearerToken);
    }

    private ResultActions patchJsonWithBearer(String body, String bearerToken) throws Exception {
        return mockMvc.perform(patch(USER_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteWithBearer(String bearerToken) throws Exception {
        return mockMvc.perform(delete(USER_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken));
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
