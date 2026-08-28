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
        return userRepository.findWithLockById(id)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /** {@link UserRepository#findWithLockById} 위임. */
    public Optional<User> findWithLockById(Long id) {
        return userRepository.findWithLockById(id);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    /** {@link UserRepository#findByProviderId} 위임. */
    public Optional<User> findByProviderId(String providerId) {
        return userRepository.findByProviderId(providerId);
    }

    /** {@link UserRepository#findIdByProviderId} 위임. */
    public Optional<Long> findIdByProviderId(String providerId) {
        return userRepository.findIdByProviderId(providerId);
    }

    /** {@link UserRepository#softDeleteById} 위임. */
    public int softDeleteById(Long userId, Instant now) {
        return userRepository.softDeleteById(userId, now);
    }

    public User saveAndFlush(User user) {
        return userRepository.saveAndFlush(user);
    }

    // ── UserConsent ──

    public UserConsent saveConsent(UserConsent consent) {
        return userConsentRepository.save(consent);
    }

    /** {@link UserConsentRepository#findLatestByUserId} 위임. */
    public List<UserConsent> findLatestConsentsByUserId(Long userId) {
        return userConsentRepository.findLatestByUserId(userId);
    }

    // ── DeletionLog ──

    public DeletionLog saveDeletionLog(DeletionLog deletionLog) {
        return deletionLogRepository.save(deletionLog);
    }

    public Optional<DeletionLog> findDeletionLogById(Long id) {
        return deletionLogRepository.findById(id);
    }

    /** {@link DeletionLogRepository#findFirstByUserIdOrderByRequestedAtDescIdDesc} 위임. */
    public Optional<DeletionLog> findLatestDeletionLogByUserId(Long userId) {
        return deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId);
    }

    /**
     * deletion_log 행 잠금 — 잠금 자체가 목적이라 아무것도 반환하지 않는다.
     * 배치의 미커밋 {@code PURGING} 선점이 있으면 커밋까지 블로킹되어야 이후의 스칼라
     * 재확인이 안전해진다. 잠금 조회의 반환 엔티티는 1차 캐시에 있으면 낡은 인스턴스라
     * 상태는 반드시 {@link #findDeletionStatusById}로 재조회할 것.
     */
    public void lockDeletionLog(Long id) {
        deletionLogRepository.findWithLockById(id);
    }

    /** {@link DeletionLogRepository#findStatusById} 위임. */
    public Optional<DeletionStatus> findDeletionStatusById(Long id) {
        return deletionLogRepository.findStatusById(id);
    }

    /** {@link DeletionLogRepository#cancelPendingPurge} 위임. */
    public int cancelPendingPurge(Long id, Instant now, Instant graceCutoff) {
        return deletionLogRepository.cancelPendingPurge(id, now, graceCutoff);
    }
}
