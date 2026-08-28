package com.aisw.kkori.user.service;

import com.aisw.kkori.auth.repository.RefreshTokenRepository;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.user.config.AccountPolicyProperties;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.dto.UserInfoResponse;
import com.aisw.kkori.user.dto.WithdrawResponse;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * 계정 조회·수정·탈퇴 (PRD {@code docs/requirements/user/account.md} 기능 1~3).
 *
 * <p>JWT 필터가 매 요청 탈퇴 유저를 차단하므로 여기 도달한 userId는 활성 유저다.
 * 다만 {@code users}에 {@code @SQLRestriction}이 없어 조회가 soft delete 레코드를
 * 걸러주지 않으므로, 방어적으로 {@code deleted_at}을 재확인한다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final int MAX_NAME_CODE_POINTS = 100;

    private final UserRepositoryService userRepositoryService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccountPolicyProperties accountPolicyProperties;
    private final Clock clock;

    /** 내 정보 조회. */
    @Transactional(readOnly = true)
    public UserInfoResponse getMyInfo(Long userId) {
        return UserInfoResponse.from(getActiveUser(userId));
    }

    /**
     * 내 정보 수정 — MVP에서 수정 가능한 필드는 name 하나다.
     *
     * <p>user 행 잠금 하에 읽고 활성 여부를 재확인한다. 잠금 없이 읽으면 flush 시점에
     * Hibernate가 전체 컬럼을 메모리 값으로 다시 써서, 그 사이 커밋된 탈퇴의
     * {@code deleted_at}을 null로 되덮어 계정이 되살아날 수 있다(PRD 기능 2 직렬화 계약).
     */
    @Transactional
    public UserInfoResponse updateName(Long userId, String name) {
        String validated = validateName(name);
        User user = userRepositoryService.findActiveWithLock(userId);
        user.updateName(validated);
        return UserInfoResponse.from(user);
    }

    /**
     * 회원 탈퇴 — soft delete + RT 전체 폐기 + 동의 철회 append + 파기 대기 등록을
     * 한 트랜잭션으로 수행한다. 네 기록 모두 같은 트랜잭션 시각을 공유한다(복구 판정·audit 정합).
     *
     * <p>동일 유저의 탈퇴 처리는 조건부 UPDATE의 영향 행 수로 직렬화한다: 상태 전이를
     * 실제로 수행한(1행) 트랜잭션만 후속 작업을 진행하고, 밀린(0행) 요청은 기존
     * {@code deleted_at} 기준의 파기 예정 시각을 반환한다(멱등).
     */
    @Transactional
    public WithdrawResponse withdraw(Long userId) {
        // user 행 잠금을 조건부 UPDATE보다 먼저 획득한다(활성 필터 없음 — 이미 탈퇴된 유저의
        // 재호출·웹훅 중복 수신도 아래 0행 분기로 멱등 응답해야 하기 때문). 잠금은 시각 순서
        // 보장용이고 상태 전이의 권위는 여전히 조건부 UPDATE의 영향 행 수다: user 잠금 하에
        // 동의를 기록하는 선택 동의 변경과 경합할 때, 잠금 전에 취득한 이른 시각으로 더 큰 id의
        // WITHDRAWN이 기록되는 시각 역행을 막는다(PRD 공통: 시각 처리).
        String providerId = userRepositoryService.findWithLockById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED))
                .getProviderId();
        // 마이크로초 절삭 — PostgreSQL timestamptz(6)는 나노초 입력을 마이크로초로 "반올림" 저장하므로
        // (Linux JDK는 나노초 시계), 절삭 없이는 응답 purgeScheduledAt과 DB deleted_at 파생값이 1µs 어긋날 수 있다
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        int transitioned = userRepositoryService.softDeleteById(userId, now);
        if (transitioned == 0) {
            // 잠금 대기 중 다른 트랜잭션이 탈퇴를 커밋한 경우, 위에서 잠근 엔티티는 1차 캐시의
            // 낡은 인스턴스일 수 있다(같은 트랜잭션이 앞서 로딩했다면 잠금 조회도 캐시 인스턴스를
            // 반환 — deleted_at이 null로 보임). softDeleteById가 clearAutomatically로 컨텍스트를
            // 비웠으므로 여기서의 재조회만이 확정 커밋 값을 읽는다.
            Instant deletedAt = userRepositoryService.findById(userId)
                    .map(User::getDeletedAt)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
            return new WithdrawResponse(purgeScheduledAt(deletedAt));
        }

        refreshTokenRepository.revokeAllByUserId(userId, now);
        withdrawAgreedConsents(userId, now);
        userRepositoryService.saveDeletionLog(DeletionLog.pending(userId, providerId, now));
        return new WithdrawResponse(purgeScheduledAt(now));
    }

    /**
     * 재동의 기반 계정 복구 (PRD 기능 4 — 한 트랜잭션에서 이 순서대로).
     * 트랜잭션 소유자는 {@code AuthService.signup}이며 이 메서드는 REQUIRED로 참여한다 —
     * {@link RestoreResult.Expired} 반환 후 호출부의 신규 생성·JWT 저장까지 원자적이어야
     * 식별정보 파기만 커밋되는 부분 반영이 없다.
     */
    @Transactional
    public RestoreResult restore(String tokenProviderId, Long deletionLogId,
                                 Map<ConsentType, ConsentDecision> consents) {
        // 1차 신원 검증 — requested_at·user_id는 불변이지만 provider_id 스냅샷은 CANCELLED/PURGED
        // 전환 시 NULL로 바뀌는 가변 값. 선조회 이후의 상태 전이는 아래 스칼라 재확인이 검출한다.
        DeletionLog deletionLog = userRepositoryService.findDeletionLogById(deletionLogId)
                .filter(log -> Objects.equals(log.getProviderId(), tokenProviderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN));

        // 잠금 순서 user → deletion_log → RT (기능 2 직렬화 계약). 로그 행 잠금이
        // 판정과 후속 상태 변경(마스킹·CANCELLED 전환) 사이의 배치 PURGING 선점을 차단한다.
        User user = userRepositoryService.findWithLockById(deletionLog.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN));
        userRepositoryService.lockDeletionLog(deletionLogId);

        // 트랜잭션 시각 — 잠금 획득 "후" 취득한다(account.md 기능 4-3). 잠금 전에 취득하면 잠금 대기 중
        // 다른 트랜잭션이 더 나중 시각으로 먼저 커밋해, 이후 기록(동의 append 등)의 시각이 커밋 순서에
        // 역행할 수 있다. 마이크로초 절삭은 withdraw와 동일한 이유(DB timestamptz(6) 반올림과의 정합).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 잠금 획득 후 스칼라 재확인 — 토큰 발급 후 10분 사이 배치가 선점했을 수 있고, 선조회 엔티티는 stale
        DeletionStatus current = userRepositoryService.findDeletionStatusById(deletionLogId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN));
        switch (current) {
            case PURGING, FAILED -> throw new BusinessException(ErrorCode.PURGE_IN_PROGRESS);
            case CANCELLED, PURGED -> throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);
            case PENDING_PURGE -> { /* 계속 진행 */ }
        }

        Duration grace = accountPolicyProperties.withdrawalGracePeriod();
        if (!now.isBefore(deletionLog.getRequestedAt().plus(grace))) {
            user.purgeIdentifiers();
            return RestoreResult.Expired.INSTANCE;
        }

        int transitioned = userRepositoryService.cancelPendingPurge(deletionLogId, now, now.minus(grace));
        if (transitioned == 0) {
            // 모든 복구 제출이 user 잠금을 먼저 잡으므로 여기 도달은 예외적 — 방어적 최후 방어선
            throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);
        }

        user.restore();
        consents.forEach((type, decision) -> userRepositoryService.saveConsent(
                UserConsent.create(user.getId(), type, decision.agreed(), decision.version(), now)));
        return new RestoreResult.Restored(user.getId());
    }

    /** 탈퇴 시점에 최신 상태가 AGREED인 모든 동의 유형에 동일 version으로 WITHDRAWN을 append한다. */
    private void withdrawAgreedConsents(Long userId, Instant now) {
        userRepositoryService.findLatestConsentsByUserId(userId).stream()
                .filter(latest -> latest.getAction() == ConsentAction.AGREED)
                .forEach(latest -> userRepositoryService.saveConsent(
                        UserConsent.create(userId, latest.getConsentType(), false, latest.getVersion(), now)));
    }

    private Instant purgeScheduledAt(Instant deletedAt) {
        return deletedAt.plus(accountPolicyProperties.withdrawalGracePeriod());
    }

    /**
     * name 검증 — 앞뒤 공백 제거 후 Unicode 코드 포인트 수 기준 1~100자.
     * {@code String.length()}(UTF-16 단위)로 세면 서로게이트 쌍 문자가 2로 계산되어
     * DB 제약(varchar(100), 코드 포인트 기준)에 들어가는 이름을 거부하게 된다(PRD 기능 2).
     */
    private String validateName(String name) {
        if (name == null) {
            throw new BusinessException(ErrorCode.INVALID_NAME);
        }
        String trimmed = name.strip();
        // PostgreSQL varchar는 NUL(\0)을 저장하지 못한다 — flush 시점 500 대신 여기서 거부
        if (trimmed.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.INVALID_NAME);
        }
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints < 1 || codePoints > MAX_NAME_CODE_POINTS) {
            throw new BusinessException(ErrorCode.INVALID_NAME);
        }
        return trimmed;
    }

    private User getActiveUser(Long userId) {
        return userRepositoryService.findById(userId)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
