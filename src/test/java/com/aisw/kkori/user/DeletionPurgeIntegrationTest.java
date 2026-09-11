package com.aisw.kkori.user;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.user.config.AccountPolicyProperties;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import com.aisw.kkori.user.service.DeletionPurgeScheduler;
import com.aisw.kkori.user.service.DeletionPurgeService;
import com.aisw.kkori.user.service.PurgeStep;
import com.aisw.kkori.user.service.PurgeTarget;
import com.aisw.kkori.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.aisw.kkori.ConcurrencyTestSupport.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파기 대상 선점·재시도 오케스트레이션 (PRD {@code docs/requirements/user/deletion.md} 기능 2 검증 기준).
 *
 * <p>오케스트레이터를 고정 Clock과 가짜 단계로 직접 구성한다 — 실제 도메인 파기 단계는 각자의 테스트가
 * 검증하고, 여기서는 선점·펜싱·실패 전환·종결 계약만 본다. 배치 빈은 테스트 컨텍스트에서 꺼져 있어
 * ({@code app.batch.enabled=false}) 백그라운드 회차가 끼어들지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class DeletionPurgeIntegrationTest extends AuthIntegrationTestSupport {

    /** 회차 시각 — 탈퇴 시각을 이 값 기준으로 시딩한다. */
    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration GRACE = Duration.ofDays(3);
    private static final Duration PURGING_TIMEOUT = Duration.ofMinutes(30);

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepositoryService userRepositoryService;

    @Autowired
    private AccountPolicyProperties accountPolicyProperties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── 가짜 단계 ──

    /** 실행 횟수를 세고 유저별 결과를 주입할 수 있는 단계. */
    private static class RecordingStep implements PurgeStep {
        final String key;
        final int order;
        final AtomicInteger executions = new AtomicInteger();
        final Function<PurgeTarget, PurgeDetail.StepResult> behavior;

        RecordingStep(String key, int order, Function<PurgeTarget, PurgeDetail.StepResult> behavior) {
            this.key = key;
            this.order = order;
            this.behavior = behavior;
        }

        static RecordingStep done(String key, int order) {
            return new RecordingStep(key, order, target -> PurgeDetail.StepResult.of(PurgeDetail.StepResult.DONE));
        }

        @Override public String key() { return key; }
        @Override public int order() { return order; }

        @Override
        public PurgeDetail.StepResult execute(PurgeTarget target) {
            executions.incrementAndGet();
            return behavior.apply(target);
        }
    }

    private DeletionPurgeService serviceAt(Instant now, PurgeStep... steps) {
        AccountPolicyProperties props = new AccountPolicyProperties(
                GRACE, accountPolicyProperties.purgeInterval(), PURGING_TIMEOUT,
                accountPolicyProperties.consentRetention());
        return new DeletionPurgeService(userRepositoryService, List.of(steps), props, transactionTemplate,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    // ── 시딩 ──

    /** 실제 탈퇴 경로로 파기 대기를 등록한 뒤 요청 시각을 회차 기준으로 되돌린다(requested_at은 updatable=false). */
    private long withdrawnUser(String providerId, Instant requestedAt) {
        User user = saveUser(providerId);
        userService.withdraw(user.getId());
        jdbcTemplate.update("update users set deleted_at = ? where id = ?", Timestamp.from(requestedAt), user.getId());
        jdbcTemplate.update("update deletion_log set requested_at = ?, updated_at = ? where user_id = ?",
                Timestamp.from(requestedAt), Timestamp.from(requestedAt), user.getId());
        return user.getId();
    }

    private long graceExpiredUser(String providerId) {
        return withdrawnUser(providerId, NOW.minus(GRACE).minus(Duration.ofHours(1)));
    }

    private DeletionLog logOf(long userId) {
        return deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId).orElseThrow();
    }

    private void setStatus(long userId, DeletionStatus status, Instant updatedAt) {
        jdbcTemplate.update("update deletion_log set status = ?, updated_at = ? where user_id = ?",
                status.name(), Timestamp.from(updatedAt), userId);
    }

    private void setDetailJson(long userId, String json) {
        jdbcTemplate.update("update deletion_log set purge_detail = ?::jsonb where user_id = ?", json, userId);
    }

    private User userRow(long userId) {
        return userRepository.findById(userId).orElseThrow();
    }

    private boolean inTx(java.util.function.Supplier<Boolean> write) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> write.get()));
    }

    // ── 검증 ──

    @Test
    @DisplayName("유예 경과 PENDING_PURGE는 한 회차에 선점→단계→PURGED로 종결되고 기록·마스킹·스냅샷 NULL이 반영된다")
    void purgesGraceExpiredPendingInOneCycle() {
        long userId = graceExpiredUser("kakao-pg-1");
        RecordingStep first = RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES);
        RecordingStep second = new RecordingStep("sessions", PurgeStep.ORDER_SESSIONS,
                target -> new PurgeDetail.StepResult(PurgeDetail.StepResult.DONE, 2, null, null, 1, 1, 0));

        serviceAt(NOW, second, first).runCycle();

        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(log.getPurgedAt()).isEqualTo(NOW);
        assertThat(log.getUpdatedAt()).isEqualTo(NOW);
        assertThat(log.getProviderId()).isNull();
        PurgeDetail detail = log.getPurgeDetail();
        assertThat(detail.attempts()).isEqualTo(1);
        assertThat(detail.lastAttemptAt()).isEqualTo(NOW);
        assertThat(detail.steps().keySet()).containsExactlyInAnyOrder("resumes", "sessions", PurgeDetail.STEP_IDENTIFIERS);
        assertThat(detail.steps().get("sessions").rows()).isEqualTo(2);
        assertThat(detail.steps().get(PurgeDetail.STEP_IDENTIFIERS).status()).isEqualTo(PurgeDetail.StepResult.DONE);
        assertThat(first.executions.get()).isEqualTo(1);
        assertThat(second.executions.get()).isEqualTo(1);

        User user = userRow(userId);
        assertThat(user.getEmail()).isNull();
        assertThat(user.getName()).isNull();
        assertThat(user.getProviderId()).isEqualTo("PURGED_" + userId);
    }

    @ParameterizedTest(name = "requested_at = now - grace {0}초 → 선점 {1}")
    @CsvSource({"0, true", "-1, false"})
    @DisplayName("유예 경계 정각은 경과(선점), 1초 전은 미경과(미선점) — 복구 가능 판정의 정확한 여집합")
    void graceBoundary(long offsetSeconds, boolean claimed) {
        long userId = withdrawnUser("kakao-pg-b", NOW.minus(GRACE).minusSeconds(offsetSeconds));
        RecordingStep step = RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES);

        serviceAt(NOW, step).runCycle();

        assertThat(logOf(userId).getStatus()).isEqualTo(claimed ? DeletionStatus.PURGED : DeletionStatus.PENDING_PURGE);
        assertThat(step.executions.get()).isEqualTo(claimed ? 1 : 0);
        assertThat(userRow(userId).getProviderId()).isEqualTo(claimed ? "PURGED_" + userId : "kakao-pg-b");
    }

    @Test
    @DisplayName("FAILED 건은 다음 회차에 재시도되어 PURGED가 되고 attempts가 이어지며 마지막 오류 기록은 유지된다")
    void retriesFailedAndKeepsLastError() {
        long userId = graceExpiredUser("kakao-pg-2");
        setStatus(userId, DeletionStatus.FAILED, NOW.minus(Duration.ofMinutes(10)));
        setDetailJson(userId, "{\"attempts\":1,\"lastError\":\"S3Exception: Access Denied\"}");

        serviceAt(NOW, RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES)).runCycle();

        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(log.getPurgeDetail().attempts()).isEqualTo(2);
        assertThat(log.getPurgeDetail().lastError()).isEqualTo("S3Exception: Access Denied");
    }

    @ParameterizedTest(name = "updated_at = now - {0}분 → 회수 {1}")
    @CsvSource({"31, true", "1, false"})
    @DisplayName("stale PURGING(임계 경과)은 재선점되고, 최근 PURGING은 건드리지 않는다")
    void reclaimsOnlyStalePurging(long ageMinutes, boolean reclaimed) {
        long userId = graceExpiredUser("kakao-pg-3");
        Instant claimedBefore = NOW.minus(Duration.ofMinutes(ageMinutes));
        setStatus(userId, DeletionStatus.PURGING, claimedBefore);
        RecordingStep step = RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES);

        serviceAt(NOW, step).runCycle();

        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(reclaimed ? DeletionStatus.PURGED : DeletionStatus.PURGING);
        assertThat(log.getUpdatedAt()).isEqualTo(reclaimed ? NOW : claimedBefore);
        assertThat(step.executions.get()).isEqualTo(reclaimed ? 1 : 0);
    }

    @Test
    @DisplayName("같은 건을 두 인스턴스가 동시에 선점해도 단계는 한 번만 실행된다")
    void concurrentClaimRunsStepsOnce() throws Exception {
        long userId = graceExpiredUser("kakao-pg-4");
        RecordingStep step = RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES);
        DeletionPurgeService instanceA = serviceAt(NOW, step);
        DeletionPurgeService instanceB = serviceAt(NOW.plusSeconds(1), step);

        runConcurrently(instanceA::runCycle, instanceB::runCycle);

        assertThat(logOf(userId).getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(step.executions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("복구 제출(잠금 후 재확인 → 조건부 CANCELLED)과 선점이 동시에 실행돼도 정확히 하나만 성립한다")
    void restoreAndClaimAreMutuallyExclusive() throws Exception {
        for (int i = 0; i < 5; i++) {
            long userId = graceExpiredUser("kakao-pg-5-" + i);
            long logId = logOf(userId).getId();
            RecordingStep step = RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES);
            AtomicBoolean restored = new AtomicBoolean();
            AtomicBoolean sawPurging = new AtomicBoolean();
            // 복구 경로의 임계 구간 재현 — user 잠금 → 로그 잠금·재확인 → 조건부 CANCELLED.
            // 유예 판정은 시계 차이로 복구 측이 아직 유예 내라고 보는 상황을 흉내 낸다(선점과 겹치는 유일한 창).
            Runnable restore = () -> transactionTemplate.executeWithoutResult(status -> {
                userRepositoryService.tryLockUser(userId);
                DeletionStatus current = userRepositoryService.lockAndReadDeletionStatus(logId).orElseThrow();
                if (current == DeletionStatus.PURGING || current == DeletionStatus.PURGED) {
                    sawPurging.set(true);
                    return;
                }
                restored.set(userRepositoryService.cancelPendingPurge(
                        logId, NOW, NOW.minus(GRACE).minus(Duration.ofDays(1))));
            });

            runConcurrently(restore, serviceAt(NOW, step)::runCycle);

            DeletionStatus finalStatus = logOf(userId).getStatus();
            if (restored.get()) {
                assertThat(finalStatus).isEqualTo(DeletionStatus.CANCELLED);
                assertThat(step.executions.get()).isZero();
                assertThat(userRow(userId).getProviderId()).isEqualTo("kakao-pg-5-" + i);
            } else {
                assertThat(sawPurging.get()).isTrue();
                assertThat(finalStatus).isEqualTo(DeletionStatus.PURGED);
                assertThat(step.executions.get()).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("단계 실패 시 FAILED·lastError·WARN이 기록되고, 3회째 실패부터 ERROR 로그가 남는다")
    void stepFailureMarksFailedAndEscalatesLog(CapturedOutput output) {
        long userId = graceExpiredUser("kakao-pg-6");
        RecordingStep failing = new RecordingStep("reports", PurgeStep.ORDER_REPORTS, target -> {
            throw new IllegalStateException("report purge exploded\nsecond line");
        });
        RecordingStep before = RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES);
        RecordingStep after = RecordingStep.done("refreshTokens", PurgeStep.ORDER_REFRESH_TOKENS);

        serviceAt(NOW, failing, before, after).runCycle();

        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.FAILED);
        assertThat(log.getUpdatedAt()).isEqualTo(NOW);
        assertThat(log.getPurgedAt()).isNull();
        assertThat(log.getProviderId()).isEqualTo("kakao-pg-6");
        PurgeDetail detail = log.getPurgeDetail();
        assertThat(detail.attempts()).isEqualTo(1);
        assertThat(detail.lastError()).isEqualTo("IllegalStateException: report purge exploded");
        assertThat(detail.steps().get("resumes").status()).isEqualTo(PurgeDetail.StepResult.DONE);
        assertThat(detail.steps().get("reports").status()).isEqualTo(PurgeDetail.StepResult.FAILED);
        assertThat(detail.steps()).doesNotContainKey("refreshTokens");
        assertThat(after.executions.get()).isZero();
        assertThat(userRow(userId).getProviderId()).isEqualTo("kakao-pg-6");
        assertThat(output.getOut()).contains("WARN").contains("파기 실패 — 다음 회차 재시도");
        assertThat(output.getOut()).doesNotContain("파기 반복 실패");

        // 3회째 — attempts 2에서 다시 실패
        setDetailJson(userId, "{\"attempts\":2}");
        serviceAt(NOW.plus(Duration.ofMinutes(10)), failing).runCycle();

        assertThat(logOf(userId).getPurgeDetail().attempts()).isEqualTo(3);
        assertThat(output.getOut()).contains("ERROR").contains("파기 반복 실패 — 운영 개입 필요");
    }

    @Test
    @DisplayName("재선점된 건에 대한 원래 인스턴스의 쓰기(기록·실패·종결·스냅샷 NULL)는 펜싱으로 전부 0행 처리된다")
    void fencingRejectsWritesFromSupersededOwner() {
        long userId = graceExpiredUser("kakao-pg-7");
        long logId = logOf(userId).getId();
        Instant staleClaim = NOW.minus(Duration.ofHours(1));
        Instant freshClaim = NOW.minus(Duration.ofMinutes(5));
        setStatus(userId, DeletionStatus.PURGING, freshClaim); // 다른 인스턴스가 재선점한 상태
        PurgeDetail stale = PurgeDetail.empty().attempt(staleClaim).withError("stale");

        // repositoryService는 트랜잭션을 소유하지 않는다 — 배치 실무와 같이 호출자 트랜잭션 안에서 실행
        assertThat(inTx(() -> userRepositoryService.recordPurgeDetail(logId, staleClaim, stale))).isFalse();
        assertThat(inTx(() -> userRepositoryService.clearProviderSnapshot(logId, staleClaim))).isFalse();
        assertThat(inTx(() -> userRepositoryService.failPurge(logId, staleClaim, NOW, stale))).isFalse();
        assertThat(inTx(() -> userRepositoryService.completePurge(logId, staleClaim, NOW, stale))).isFalse();

        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PURGING);
        assertThat(log.getUpdatedAt()).isEqualTo(freshClaim);
        assertThat(log.getProviderId()).isEqualTo("kakao-pg-7");
        assertThat(log.getPurgeDetail()).isNull();

        // 현재 소유자의 쓰기는 통과한다
        assertThat(inTx(() -> userRepositoryService.completePurge(logId, freshClaim, NOW, PurgeDetail.empty()))).isTrue();
        assertThat(logOf(userId).getStatus()).isEqualTo(DeletionStatus.PURGED);
    }

    @Test
    @DisplayName("한 유저의 실패가 같은 회차의 다른 유저 파기를 막지 않는다")
    void isolatesFailurePerUser() {
        long failingUser = graceExpiredUser("kakao-pg-8a");
        long healthyUser = graceExpiredUser("kakao-pg-8b");
        RecordingStep step = new RecordingStep("resumes", PurgeStep.ORDER_RESUMES, target -> {
            if (target.userId() == failingUser) {
                throw new IllegalStateException("only this user fails");
            }
            return PurgeDetail.StepResult.of(PurgeDetail.StepResult.DONE);
        });

        serviceAt(NOW, step).runCycle();

        assertThat(logOf(failingUser).getStatus()).isEqualTo(DeletionStatus.FAILED);
        assertThat(logOf(healthyUser).getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(step.executions.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("CANCELLED·PURGED 건은 스캔·선점되지 않는다")
    void skipsTerminalLogs() {
        long cancelled = graceExpiredUser("kakao-pg-9a");
        long purged = graceExpiredUser("kakao-pg-9b");
        setStatus(cancelled, DeletionStatus.CANCELLED, NOW.minus(Duration.ofDays(1)));
        setStatus(purged, DeletionStatus.PURGED, NOW.minus(Duration.ofDays(1)));
        RecordingStep step = RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES);

        serviceAt(NOW, step).runCycle();

        assertThat(step.executions.get()).isZero();
        assertThat(logOf(cancelled).getStatus()).isEqualTo(DeletionStatus.CANCELLED);
        assertThat(logOf(purged).getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(logOf(cancelled).getUpdatedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("users 행이 없는 모순 건은 ERROR 로그 후 식별정보 단계를 SKIPPED로 기록하고 종결한다")
    void completesWithSkippedIdentifiersWhenUserRowIsMissing(CapturedOutput output) {
        long userId = graceExpiredUser("kakao-pg-10");
        jdbcTemplate.update("delete from user_consent where user_id = ?", userId);
        jdbcTemplate.update("delete from refresh_token where user_id = ?", userId);
        jdbcTemplate.update("delete from users where id = ?", userId);

        serviceAt(NOW, RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES)).runCycle();

        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(log.getPurgeDetail().steps().get(PurgeDetail.STEP_IDENTIFIERS).status())
                .isEqualTo(PurgeDetail.StepResult.SKIPPED);
        assertThat(output.getOut()).contains("ERROR").contains("users 행 부재");
    }

    @Test
    @DisplayName("회차 시각은 마이크로초로 절삭되어 DB 기록과 정확히 일치한다 (펜싱 등가 비교의 전제)")
    void claimedAtSurvivesRoundTrip() {
        long userId = graceExpiredUser("kakao-pg-11");
        Instant nanoNow = NOW.plusNanos(123_456_789);

        serviceAt(nanoNow, RecordingStep.done("resumes", PurgeStep.ORDER_RESUMES)).runCycle();

        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(log.getPurgedAt()).isEqualTo(nanoNow.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("스케줄러는 account.purge-interval을 fixedDelay로 쓴다 (배치 빈은 테스트 컨텍스트에서 미등록)")
    void schedulerIsWiredToConfiguredInterval() throws Exception {
        Scheduled scheduled = DeletionPurgeScheduler.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${account.purge-interval}");
        assertThat(scheduled.fixedRateString()).isEmpty();
    }
}
