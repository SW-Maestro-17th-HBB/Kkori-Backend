package com.aisw.kkori.user.repositoryservice;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.repository.DeletionLogRepository;
import com.aisw.kkori.user.repository.UserConsentRepository;
import com.aisw.kkori.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * user 도메인 영속성 접근 계층. service·타 도메인은 raw repository 대신 이 계층을 거친다
 * (CLAUDE.md 패키지 구조 규칙). 트랜잭션은 소유하지 않는다 — 잠금 메서드는 반드시
 * 호출자의 트랜잭션 안에서 호출해야 잠금이 트랜잭션 끝까지 유지된다.
 */
@Service
@RequiredArgsConstructor
public class UserRepositoryService {

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final DeletionLogRepository deletionLogRepository;

    // ── User ──

    /**
     * user 행 잠금 + 활성 재확인 — "잠금 후 활성 재확인" 관용구의 단일 정의.
     * 유저 상태에 의존하는 쓰기 경로(정보 수정·동의 변경·세션 생성·이력서 수정/재분석)가
     * 공유하는 직렬화 지점으로, 탈퇴가 선점했으면 {@code UNAUTHORIZED}를 던진다.
     * 의도적으로 다른 변형(탈퇴의 무필터 멱등 잠금, 재발급의 RT_NOT_FOUND)은 이 헬퍼를 쓰지 않는다.
     */
    public User findActiveWithLock(Long id) {
        return lockActive(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /** user 행 잠금 + 활성 필터 — 활성이 아닐 때의 에러 선택이 호출자마다 다른 경로(재발급)용. */
    public Optional<User> lockActive(Long id) {
        return userRepository.findWithLockById(id)
                .filter(user -> !user.isDeleted());
    }

    /**
     * user 행 잠금 — 잠금 자체가 목적이라 아무것도 반환하지 않는다. 활성 재확인 없음:
     * 세션 전이·탈퇴 재호출처럼 탈퇴 유저의 흐름도 직렬화만 하고 통과시키는 경로용.
     */
    public void lockUser(Long id) {
        userRepository.findWithLockById(id);
    }

    /** user 행 잠금(무필터) — 잠근 엔티티가 필요한 경로(탈퇴·복구)용. 부재 시의 에러는 호출자가 정한다. */
    public Optional<User> lockUserIgnoringDeleted(Long id) {
        return userRepository.findWithLockById(id);
    }

    /**
     * 카카오 회원번호로 user 행을 잠근다 — id를 스칼라로 먼저 조회한 뒤 잠금 조회한다.
     * 엔티티로 먼저 조회하면 잠금 조회가 1차 캐시의 낡은 관리 인스턴스를 반환할 수 있다.
     */
    public Optional<User> lockUserByProviderId(String providerId) {
        return userRepository.findIdByProviderId(providerId)
                .flatMap(userRepository::findWithLockById);
    }

    /** 활성 유저 조회 — soft delete된 유저는 없는 것으로 취급한다. */
    public Optional<User> findActiveById(Long id) {
        return userRepository.findById(id)
                .filter(user -> !user.isDeleted());
    }

    /** 활성 유저 조회 — 부재·탈퇴는 {@code UNAUTHORIZED}. */
    public User getActive(Long id) {
        return findActiveById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    public Optional<User> findByProviderId(String providerId) {
        return userRepository.findByProviderId(providerId);
    }

    public User saveAndFlush(User user) {
        return userRepository.saveAndFlush(user);
    }

    /**
     * 조건부 soft delete — 활성 상태일 때만 전이된다. 전이가 0행이면(이미 탈퇴) 확정 커밋된
     * {@code deleted_at}을 재조회해 돌려준다 — 조건부 UPDATE가 영속성 컨텍스트를 비우므로
     * 이 재조회만이 잠금 대기 중 커밋된 최신 값을 읽는다. 재조회 부재는 {@code UNAUTHORIZED}.
     */
    public SoftDeleteResult softDelete(Long userId, Instant now) {
        int transitioned = userRepository.softDeleteById(userId, now);
        if (transitioned == 1) {
            return new SoftDeleteResult(true, now);
        }
        Instant deletedAt = userRepository.findById(userId)
                .map(User::getDeletedAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return new SoftDeleteResult(false, deletedAt);
    }

    /** {@code transitioned=false}면 이미 탈퇴된 상태였고 {@code deletedAt}은 기존 탈퇴 시각이다. */
    public record SoftDeleteResult(boolean transitioned, Instant deletedAt) {
    }

    // ── UserConsent ──

    public UserConsent saveConsent(UserConsent consent) {
        return userConsentRepository.save(consent);
    }

    public List<UserConsent> findLatestConsentsByUserId(Long userId) {
        return userConsentRepository.findLatestByUserId(userId);
    }

    // ── DeletionLog ──

    public DeletionLog saveDeletionLog(DeletionLog deletionLog) {
        return deletionLogRepository.save(deletionLog);
    }

    /**
     * 탈퇴 로그 조회 + provider_id 스냅샷 대조(1차 신원 검증) — 부재·불일치는
     * {@code INVALID_SIGNUP_TOKEN}. 스냅샷은 CANCELLED/PURGED 전환 시 NULL로 바뀌는
     * 가변 값이라, 선조회 이후의 상태 전이는 {@link #lockAndReadDeletionStatus} 재확인이 검출한다.
     */
    public DeletionLog getDeletionLogMatching(Long id, String providerId) {
        return deletionLogRepository.findById(id)
                .filter(log -> Objects.equals(log.getProviderId(), providerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN));
    }

    public Optional<DeletionLog> findLatestDeletionLog(Long userId) {
        return deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId);
    }

    /**
     * deletion_log 행을 잠근 뒤 현재 status를 스칼라로 재조회한다. 잠금은 배치의 미커밋
     * {@code PURGING} 선점이 커밋될 때까지 블로킹해 재조회를 안전하게 만들고, 스칼라 재조회는
     * 1차 캐시의 낡은 엔티티를 우회한다. 레코드 소실은 empty.
     */
    public Optional<DeletionStatus> lockAndReadDeletionStatus(Long id) {
        deletionLogRepository.findWithLockById(id);
        return deletionLogRepository.findStatusById(id);
    }

    public int cancelPendingPurge(Long id, Instant now, Instant graceCutoff) {
        return deletionLogRepository.cancelPendingPurge(id, now, graceCutoff);
    }
}
