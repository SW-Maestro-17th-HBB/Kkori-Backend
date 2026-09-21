package com.aisw.kkori.user.repositoryservice;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.repository.DeletionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * user 도메인의 {@code deletion_log} 영속성 접근 계층 — 탈퇴 기록(account.md 기능 3·5)과 파기 배치의 상태 머신
 * (deletion.md 기능 2·3·7)이 쓰는 접근을 {@link UserRepositoryService}에서 분리했다. 트랜잭션은 소유하지 않는다 —
 * 잠금 메서드는 호출자의 트랜잭션 안에서만 호출한다.
 *
 * <p>선점({@link #claimForPurge}) 이후의 모든 쓰기는 선점 시각 {@code claimedAt}을 펜싱 토큰으로 대조한다 —
 * 재선점된(stale) 실행자의 늦은 쓰기는 0행으로 끝나 {@code false}를 돌려받는다.
 */
@Service
@RequiredArgsConstructor
public class DeletionLogRepositoryService {

    private final DeletionLogRepository deletionLogRepository;

    // ── 탈퇴 기록·복구 (account.md 기능 3·5) ──

    public DeletionLog save(DeletionLog deletionLog) {
        return deletionLogRepository.save(deletionLog);
    }

    /**
     * 탈퇴 로그 조회 + provider_id 스냅샷 대조(1차 신원 검증) — 부재·불일치는
     * {@code INVALID_SIGNUP_TOKEN}. 스냅샷은 CANCELLED/PURGED 전환 시 NULL로 바뀌는
     * 가변 값이라, 선조회 이후의 상태 전이는 {@link #lockAndReadStatus} 재확인이 검출한다.
     */
    public DeletionLog getMatching(Long id, String providerId) {
        return deletionLogRepository.findById(id)
                .filter(log -> Objects.equals(log.getProviderId(), providerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN));
    }

    public Optional<DeletionLog> findLatestByUserId(Long userId) {
        return deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId);
    }

    /**
     * deletion_log 행을 잠근 뒤 현재 status를 스칼라로 재조회한다. 잠금은 배치의 미커밋
     * {@code PURGING} 선점이 커밋될 때까지 블로킹해 재조회를 안전하게 만들고, 스칼라 재조회는
     * 1차 캐시의 낡은 엔티티를 우회한다. 레코드 소실은 empty.
     */
    public Optional<DeletionStatus> lockAndReadStatus(Long id) {
        deletionLogRepository.findWithLockById(id);
        return deletionLogRepository.findStatusById(id);
    }

    public boolean cancelPendingPurge(Long id, Instant now, Instant graceCutoff) {
        return deletionLogRepository.cancelPendingPurge(id, now, graceCutoff) == 1;
    }

    // ── 파기 배치 (PRD deletion.md 기능 2) — 선점 이후의 모든 쓰기는 claimedAt 펜싱 ──

    /** 파기 후보 — 유예 경과 PENDING_PURGE · FAILED · stale PURGING. 요청 시각 오름차순. */
    public List<DeletionLog> findPurgeCandidates(Instant graceCutoff, Instant staleCutoff) {
        return deletionLogRepository.findPurgeCandidates(graceCutoff, staleCutoff);
    }

    /** 조건부 선점 — 선점 여부를 반환한다. {@code claimedAt}이 이 건의 펜싱 토큰이 된다. */
    public boolean claimForPurge(Long id, Instant claimedAt, Instant graceCutoff, Instant staleCutoff) {
        return deletionLogRepository.claimForPurge(id, claimedAt, graceCutoff, staleCutoff) == 1;
    }

    /** 현재 파기 기록 — 선점 직후(같은 트랜잭션, 행 잠금 보유)에 읽어 시도 횟수를 잇는다. */
    public Optional<PurgeDetail> findPurgeDetail(Long id) {
        return deletionLogRepository.findById(id).map(DeletionLog::getPurgeDetail);
    }

    /** 중간 기록 — 펜스 불일치(재선점됨)면 false. */
    public boolean recordPurgeDetail(Long id, Instant claimedAt, PurgeDetail detail) {
        return deletionLogRepository.recordPurgeDetail(id, claimedAt, detail) == 1;
    }

    /** unlink 재료인 회원번호 스냅샷 — NULL(완료·복구·미기록)이면 empty. 매 시도마다 새로 읽는다. */
    public Optional<String> findProviderSnapshot(Long id) {
        return deletionLogRepository.findById(id).map(DeletionLog::getProviderId);
    }

    /** unlink 완료·생략 후 스냅샷 제거 — 펜스 불일치면 false. */
    public boolean clearProviderSnapshot(Long id, Instant claimedAt) {
        return deletionLogRepository.clearProviderSnapshot(id, claimedAt) == 1;
    }

    /** PURGING → FAILED — 펜스 불일치면 false. */
    public boolean failPurge(Long id, Instant claimedAt, Instant now, PurgeDetail detail) {
        return deletionLogRepository.failPurge(id, claimedAt, now, detail) == 1;
    }

    /** PURGING → PURGED — 펜스 불일치면 false. */
    public boolean completePurge(Long id, Instant claimedAt, Instant now, PurgeDetail detail) {
        return deletionLogRepository.completePurge(id, claimedAt, now, detail) == 1;
    }

    // ── 보존 만료 정리 (PRD deletion.md 기능 7) ──

    /** 파기 완료 후 보존 기간이 지났고 가명 users 행이 남아 있는 건. */
    public List<DeletionLog> findRetentionExpiredPurges(Instant retentionCutoff) {
        return deletionLogRepository.findRetentionExpired(retentionCutoff);
    }
}
