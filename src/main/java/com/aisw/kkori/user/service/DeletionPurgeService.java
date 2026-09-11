package com.aisw.kkori.user.service;

import com.aisw.kkori.user.config.AccountPolicyProperties;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 파기 배치 오케스트레이터 (PRD deletion.md 기능 2·3) — 스캔 → 건별 {선점 → 단계 → 종결}.
 *
 * <p>다중 인스턴스 동시 실행은 조건부 선점(영향 행 수)과 선점 시각 펜싱으로 무해화한다.
 * 선점·각 단계·종결·실패 전환은 각각 짧은 트랜잭션이며 S3·카카오 왕복은 잠금 밖에서 일어난다.
 * 건별 처리는 격리한다 — 한 유저의 실패가 같은 회차의 다른 유저를 막지 않는다.
 *
 * <p>스케줄 트리거는 {@link DeletionPurgeScheduler}가 담당하고, 테스트는 {@link #runCycle()}을
 * 직접 호출한다(스위퍼 관례).
 */
@Slf4j
@Service
public class DeletionPurgeService {

    /** 이 횟수부터 실패 로그를 ERROR로 올린다 — 일시 장애가 아닌 구조적 실패로 보고 운영 개입을 요구. */
    static final int ERROR_LOG_ATTEMPT_THRESHOLD = 3;
    private static final int MAX_ERROR_SUMMARY_LENGTH = 200;

    private final UserRepositoryService userRepositoryService;
    private final List<PurgeStep> steps;
    private final AccountPolicyProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DeletionPurgeService(UserRepositoryService userRepositoryService, List<PurgeStep> steps,
                                AccountPolicyProperties properties, TransactionTemplate transactionTemplate,
                                Clock clock) {
        this.userRepositoryService = userRepositoryService;
        this.steps = steps.stream().sorted(Comparator.comparingInt(PurgeStep::order)).toList();
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 한 회차 — 회차 시각 하나로 후보를 판정하고, 건별 기록 시각은 각 트랜잭션에서 따로 취득한다. */
    public void runCycle() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant graceCutoff = now.minus(properties.withdrawalGracePeriod());
        Instant staleCutoff = now.minus(properties.purgingTimeout());
        List<DeletionLog> candidates = userRepositoryService.findPurgeCandidates(graceCutoff, staleCutoff);
        for (DeletionLog candidate : candidates) {
            try {
                purge(candidate, graceCutoff, staleCutoff);
            } catch (RuntimeException e) {
                // 선점·실패 전환 자체가 실패한 경우(DB 장애 등) — 상태는 그대로이므로 다음 회차가 다시 본다
                log.warn("파기 건 처리 중 예외 — 다음 회차 재시도 (deletionLogId={}, userId={}): {}",
                        candidate.getId(), candidate.getUserId(), e.getClass().getSimpleName());
            }
        }
    }

    private void purge(DeletionLog candidate, Instant graceCutoff, Instant staleCutoff) {
        Optional<Claim> claimed = claim(candidate.getId(), graceCutoff, staleCutoff);
        if (claimed.isEmpty()) {
            return; // 스캔~선점 사이에 복구·타 인스턴스 선점 — 이번 회차는 건너뛴다
        }
        PurgeTarget target = new PurgeTarget(candidate.getId(), candidate.getUserId(), claimed.get().claimedAt());
        PurgeDetail detail = claimed.get().detail();
        log.info("파기 선점 (deletionLogId={}, userId={}, attempts={})",
                target.deletionLogId(), target.userId(), detail.attempts());

        for (PurgeStep step : steps) {
            try {
                detail = detail.withStep(step.key(), step.execute(target));
                record(target, detail);
            } catch (OwnershipLostException e) {
                log.warn("파기 소유권 상실 — 재선점된 건, 이번 인스턴스의 결과 폐기 (deletionLogId={}, step={})",
                        target.deletionLogId(), step.key());
                return;
            } catch (RuntimeException e) {
                fail(target, detail.withStep(step.key(), PurgeDetail.StepResult.failed()).withError(summarize(e)), e);
                return;
            }
        }
        try {
            complete(target, detail);
        } catch (RuntimeException e) {
            fail(target, detail.withError(summarize(e)), e);
        }
    }

    /** 선점 트랜잭션 — 조건부 UPDATE로 소유권을 얻고, 같은 트랜잭션에서 시도 횟수·시각을 기록한다. */
    private Optional<Claim> claim(long deletionLogId, Instant graceCutoff, Instant staleCutoff) {
        return transactionTemplate.execute(status -> {
            Instant claimedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (!userRepositoryService.claimForPurge(deletionLogId, claimedAt, graceCutoff, staleCutoff)) {
                return Optional.empty();
            }
            PurgeDetail detail = userRepositoryService.findPurgeDetail(deletionLogId)
                    .orElse(PurgeDetail.empty())
                    .attempt(claimedAt);
            userRepositoryService.recordPurgeDetail(deletionLogId, claimedAt, detail);
            return Optional.of(new Claim(claimedAt, detail));
        });
    }

    private void record(PurgeTarget target, PurgeDetail detail) {
        Boolean recorded = transactionTemplate.execute(status ->
                userRepositoryService.recordPurgeDetail(target.deletionLogId(), target.claimedAt(), detail));
        if (!Boolean.TRUE.equals(recorded)) {
            throw new OwnershipLostException();
        }
    }

    /**
     * 종결 트랜잭션 — user 행 잠금 → (잠금 후 시각) → 식별정보 마스킹 → 펜싱 PURGED 전환.
     * users 행 부재는 도달 불가한 모순이라 ERROR 로그 후 식별정보 단계를 생략하고 종결한다.
     */
    private void complete(PurgeTarget target, PurgeDetail detail) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<User> user = userRepositoryService.tryLockUser(target.userId());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            PurgeDetail finalDetail;
            if (user.isPresent()) {
                user.get().purgeIdentifiers();
                finalDetail = detail.withStep(PurgeDetail.STEP_IDENTIFIERS,
                        PurgeDetail.StepResult.of(PurgeDetail.StepResult.DONE));
            } else {
                log.error("파기 종결 — users 행 부재(모순 상태), 식별정보 단계 생략 (deletionLogId={}, userId={})",
                        target.deletionLogId(), target.userId());
                finalDetail = detail.withStep(PurgeDetail.STEP_IDENTIFIERS,
                        PurgeDetail.StepResult.of(PurgeDetail.StepResult.SKIPPED));
            }
            if (!userRepositoryService.completePurge(target.deletionLogId(), target.claimedAt(), now, finalDetail)) {
                status.setRollbackOnly();
                throw new OwnershipLostException();
            }
            log.info("파기 완료 (deletionLogId={}, userId={}, attempts={})",
                    target.deletionLogId(), target.userId(), finalDetail.attempts());
        });
    }

    private void fail(PurgeTarget target, PurgeDetail detail, RuntimeException cause) {
        Boolean marked = transactionTemplate.execute(status -> userRepositoryService.failPurge(
                target.deletionLogId(), target.claimedAt(), clock.instant().truncatedTo(ChronoUnit.MICROS), detail));
        if (!Boolean.TRUE.equals(marked)) {
            log.warn("파기 실패 전환 펜싱 불일치 — 재선점된 건, 결과 폐기 (deletionLogId={})", target.deletionLogId());
            return;
        }
        if (detail.attempts() >= ERROR_LOG_ATTEMPT_THRESHOLD) {
            log.error("파기 반복 실패 — 운영 개입 필요 (deletionLogId={}, userId={}, attempts={}, lastError={})",
                    target.deletionLogId(), target.userId(), detail.attempts(), detail.lastError(), cause);
        } else {
            log.warn("파기 실패 — 다음 회차 재시도 (deletionLogId={}, userId={}, attempts={}, lastError={})",
                    target.deletionLogId(), target.userId(), detail.attempts(), detail.lastError(), cause);
        }
    }

    /** 예외 요약 — 클래스명 + 첫 줄 메시지, 길이 제한. 외부 응답 본문이 통째로 실리지 않게 한다(공통: 로그·개인정보). */
    static String summarize(Throwable e) {
        String message = e.getMessage();
        String summary = message == null ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message.lines().findFirst().orElse("");
        return summary.length() <= MAX_ERROR_SUMMARY_LENGTH
                ? summary : summary.substring(0, MAX_ERROR_SUMMARY_LENGTH) + "...";
    }

    private record Claim(Instant claimedAt, PurgeDetail detail) {
    }

    /** 펜싱 불일치 — 다른 인스턴스가 stale 회수로 이 건을 재선점했다. 실패 전환도 하지 않는다(그쪽이 소유자). */
    private static class OwnershipLostException extends RuntimeException {
        OwnershipLostException() {
            super("purge ownership lost");
        }
    }
}
