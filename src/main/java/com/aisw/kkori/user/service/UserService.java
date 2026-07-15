package com.aisw.kkori.user.service;

import com.aisw.kkori.auth.repository.RefreshTokenRepository;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.user.config.AccountPolicyProperties;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.dto.UserInfoResponse;
import com.aisw.kkori.user.dto.WithdrawResponse;
import com.aisw.kkori.user.repository.DeletionLogRepository;
import com.aisw.kkori.user.repository.UserConsentRepository;
import com.aisw.kkori.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DeletionLogRepository deletionLogRepository;
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
        User user = userRepository.findWithLockById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
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
        // 마이크로초 절삭 — PostgreSQL timestamptz(6)는 나노초 입력을 마이크로초로 "반올림" 저장하므로
        // (Linux JDK는 나노초 시계), 절삭 없이는 응답 purgeScheduledAt과 DB deleted_at 파생값이 1µs 어긋날 수 있다
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // 활성 필터 없이 조회한다 — 이미 탈퇴된 유저의 재호출도 멱등 응답해야 하기 때문(아래 0행 분기)
        String providerId = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED))
                .getProviderId();

        int transitioned = userRepository.softDeleteById(userId, now);
        if (transitioned == 0) {
            Instant deletedAt = userRepository.findById(userId)
                    .map(User::getDeletedAt)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
            return new WithdrawResponse(purgeScheduledAt(deletedAt));
        }

        refreshTokenRepository.revokeAllByUserId(userId, now);
        withdrawAgreedConsents(userId, now);
        deletionLogRepository.save(DeletionLog.pending(userId, providerId, now));
        return new WithdrawResponse(purgeScheduledAt(now));
    }

    /** 탈퇴 시점에 최신 상태가 AGREED인 모든 동의 유형에 동일 version으로 WITHDRAWN을 append한다. */
    private void withdrawAgreedConsents(Long userId, Instant now) {
        Collection<UserConsent> latestByType = userConsentRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(UserConsent::getConsentType, Function.identity(),
                        (a, b) -> a.getId() > b.getId() ? a : b))
                .values();
        latestByType.stream()
                .filter(latest -> latest.getAction() == ConsentAction.AGREED)
                .forEach(latest -> userConsentRepository.save(
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
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints < 1 || codePoints > MAX_NAME_CODE_POINTS) {
            throw new BusinessException(ErrorCode.INVALID_NAME);
        }
        return trimmed;
    }

    private User getActiveUser(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
