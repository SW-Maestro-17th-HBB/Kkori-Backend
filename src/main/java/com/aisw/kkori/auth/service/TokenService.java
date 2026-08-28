package com.aisw.kkori.auth.service;

import com.aisw.kkori.auth.domain.RefreshToken;
import com.aisw.kkori.auth.dto.KakaoLoginResponse;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.auth.repositoryservice.AuthRepositoryService;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.jwt.JwtProperties;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.global.jwt.TokenHasher;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.config.AccountPolicyProperties;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * RTR·재사용 감지의 핵심 로직.
 *
 * {@link AuthService}와 분리된 이유: 카카오 HTTP 왕복 동안 DB 커넥션을 붙잡지 않도록
 * 트랜잭션 경계를 DB 작업에만 좁히기 위함이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final Duration GRACE_PERIOD = Duration.ofSeconds(60);

    private final UserRepositoryService userRepositoryService;
    private final AuthRepositoryService authRepositoryService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final AccountPolicyProperties accountPolicyProperties;
    private final Clock clock;

    /**
     * 카카오 신원으로 신규/기존/복구 대상/유예 만료를 판정한다 (PRD account.md 기능 4 상태별 정책).
     *
     * <p>판정·토큰 발급은 user 행 잠금 하에 수행한다(잠금 순서 user → deletion_log → RT) —
     * 잠금 없이 조회 후 RT를 INSERT하면 탈퇴의 RT 전량 폐기와 경합해 탈퇴 후 활성 RT가 남을 수 있고,
     * 유예 만료 경로는 식별정보를 변경하므로 잠금 없는 flush가 탈퇴를 되덮을 수 있다.
     * id는 스칼라로 조회한다 — 엔티티 조회는 잠금 조회가 낡은 관리 인스턴스를 반환하게 만든다.
     */
    @Transactional
    public KakaoLoginResponse processKakaoLogin(KakaoUserInfo info) {
        return userRepositoryService.lockUserByProviderId(info.providerId())
                .map(user -> user.isDeleted()
                        ? judgeDeletedUser(user, info)
                        : KakaoLoginResponse.loggedIn(issueTokenPair(user.getId())))
                .orElseGet(() -> KakaoLoginResponse.newUser(
                        jwtTokenProvider.createSignupToken(info.providerId(), info.email(), info.nickname())));
    }

    /**
     * 탈퇴 계정의 상태별 판정 — user 행 잠금 보유 상태에서 호출된다.
     * 복구는 여기서 수행하지 않는다: 복구용 signup token만 발급하고,
     * 실제 복구는 재동의 제출({@code /auth/signup}) 시점에 성립한다.
     *
     * <p>판정 대상 로그 행도 잠근다(잠금 순서 user → deletion_log) — 잠금 없이 읽으면
     * 판정 직후 배치가 {@code PURGING}으로 선점해도 마스킹·신규 발급이 계속 진행되어
     * 409 계약이 깨진다. 상태는 잠금 획득 후 스칼라로 재조회한다(1차 캐시 우회).
     */
    private KakaoLoginResponse judgeDeletedUser(User user, KakaoUserInfo info) {
        Long latestLogId = latestDeletionLogId(user);
        DeletionStatus status = lockAndReadStatus(latestLogId);

        if (status == DeletionStatus.PURGING || status == DeletionStatus.FAILED) {
            // 배치가 unlink 소유권을 쥔 상태 — 신규 가입을 허용하면 새 카카오 연결이 끊기는 경합
            throw new BusinessException(ErrorCode.PURGE_IN_PROGRESS);
        }
        if (status == DeletionStatus.PENDING_PURGE) {
            return respondWithBoundToken(user, info, latestLogId);
        }
        return absorbAnomalyAsNewUser(user, info, status);
    }

    private Long latestDeletionLogId(User user) {
        return userRepositoryService
                .findLatestDeletionLog(user.getId())
                .map(DeletionLog::getId)
                .orElse(null);
    }

    /** 로그 행을 잠근 뒤 현재 상태를 읽는다. 로그가 없으면 null. */
    private DeletionStatus lockAndReadStatus(Long deletionLogId) {
        if (deletionLogId == null) {
            return null;
        }
        return userRepositoryService.lockAndReadDeletionStatus(deletionLogId).orElse(null);
    }

    /**
     * 유예 내·만료 모두 계정을 변경하지 않고 해당 탈퇴 건에 바인딩된 토큰만 발급한다.
     * 만료 시점에 여기서 마스킹하면 제출 전 재로그인이 provider_id 매칭에 실패해
     * 바인딩 없는 완전 신규로 판정되어 배치 선점(PURGING)의 409 차단을 우회한다 —
     * 식별정보 파기·신규 생성은 제출 트랜잭션(UserService.restore)이 잠금 하 재판정 후 수행한다.
     */
    private KakaoLoginResponse respondWithBoundToken(User user, KakaoUserInfo info, Long deletionLogId) {
        String boundToken = jwtTokenProvider.createSignupToken(
                info.providerId(), info.email(), info.nickname(), deletionLogId);
        boolean withinGrace = clock.instant()
                .isBefore(user.getDeletedAt().plus(accountPolicyProperties.withdrawalGracePeriod()));
        return withinGrace
                ? KakaoLoginResponse.restoreRequired(boundToken)
                : KakaoLoginResponse.newUser(boundToken);
    }

    /** 로그 부재·종결 상태 모순 — 활성 로그가 없어 바인딩 대상도 배치 경합도 없다. 여기서만 즉시 파기한다. */
    private KakaoLoginResponse absorbAnomalyAsNewUser(User user, KakaoUserInfo info, DeletionStatus status) {
        log.error("탈퇴 계정의 deletion_log가 활성 상태가 아닙니다 — 데이터 모순, 신규 전환으로 흡수. userId={}, status={}",
                user.getId(), status);
        user.purgeIdentifiers();
        return KakaoLoginResponse.newUser(
                jwtTokenProvider.createSignupToken(info.providerId(), info.email(), info.nickname()));
    }

    /**
     * AT·RT 쌍을 발급하고 RT를 해시로 저장한다.
     *
     * <p>iat은 초 단위로 절삭한다 — JWT의 iat/exp가 초 정밀도라, 저장값과 토큰 claim이
     * 정확히 일치해야 Grace Period의 결정적 재생성이 성립한다.
     */
    @Transactional
    public TokenResponse issueTokenPair(Long userId) {
        String jti = UUID.randomUUID().toString();
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(jwtProperties.refreshTokenTtl());

        String refreshToken = jwtTokenProvider.createRefreshToken(userId, jti, issuedAt, expiresAt);
        authRepositoryService.save(
                RefreshToken.issue(userId, TokenHasher.sha256Hex(refreshToken), jti, issuedAt, expiresAt));

        return new TokenResponse(jwtTokenProvider.createAccessToken(userId), refreshToken);
    }

    /**
     * RTR 재발급. 전달된 RT의 해시 조회가 유일한 검증이며 JWT 파싱은 하지 않는다
     *
     * 검사 순서: 미존재 → 폐기(재사용/Grace) → 만료 → 회전. 폐기를 만료보다 먼저 보는 이유는
     * 회전된 뒤 만료된 토큰도 재사용 신호를 담고 있기 때문이다.
     *
     * <p>잠금 순서는 user 행 → RT 행이다. RT를 먼저 잠그면 탈퇴의 RT 전량 폐기(벌크 UPDATE)가
     * 대기하는 사이 새 RT를 INSERT할 수 있고, 대기하던 폐기 쿼리는 그 새 RT를 보지 못해
     * 탈퇴 후에도 활성 RT가 남는다. user 잠금 후 활성 여부를 재확인해 탈퇴 유저를 거부한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse reissue(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.RT_NOT_FOUND);
        }
        String tokenHash = TokenHasher.sha256Hex(refreshToken);
        // 스칼라 조회 — 엔티티로 읽으면 아래 잠금 조회가 낡은 관리 인스턴스를 반환할 수 있다
        Long userId = authRepositoryService.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.RT_NOT_FOUND));
        boolean active = userRepositoryService.tryLockActive(userId).isPresent();
        if (!active) {
            throw new BusinessException(ErrorCode.RT_NOT_FOUND);
        }
        RefreshToken current = authRepositoryService.findWithLockByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.RT_NOT_FOUND));
        Instant now = clock.instant();

        if (current.isRevoked()) {
            return handleRevokedToken(current, now);
        }
        if (current.isExpired(now)) {
            throw new BusinessException(ErrorCode.RT_EXPIRED);
        }

        TokenResponse tokens = issueTokenPair(current.getUserId());
        current.rotateTo(TokenHasher.sha256Hex(tokens.refreshToken()), now);
        return tokens;
    }

    private TokenResponse handleRevokedToken(RefreshToken current, Instant now) {
        if (current.getReplacedByTokenHash() == null) {
            throw new BusinessException(ErrorCode.RT_NOT_FOUND);
        }

        boolean withinGrace = Duration.between(current.getRevokedAt(), now).compareTo(GRACE_PERIOD) <= 0;
        if (withinGrace) {
            RefreshToken replacement = authRepositoryService
                    .findValidReplacement(current.getReplacedByTokenHash(), now)
                    .orElse(null);
            if (replacement != null) {
                return new TokenResponse(
                        jwtTokenProvider.createAccessToken(replacement.getUserId()),
                        regenerate(replacement));
            }
            throw new BusinessException(ErrorCode.RT_NOT_FOUND);
        }

        authRepositoryService.revokeAllByUserId(current.getUserId(), now);
        throw new BusinessException(ErrorCode.RT_REUSE_DETECTED);
    }

    /**
     * 저장된 정보(userId·jti·created_at=iat·expired_at=exp)로 RT 문자열을 재생성한다.
     * 재생성 결과의 해시가 저장 해시와 다르면 재료·키가 어긋난 것이므로 500으로 실패시킨다.
     */
    private String regenerate(RefreshToken replacement) {
        String regenerated = jwtTokenProvider.createRefreshToken(
                replacement.getUserId(), replacement.getJti(),
                replacement.getCreatedAt(), replacement.getExpiredAt());
        if (!TokenHasher.sha256Hex(regenerated).equals(replacement.getTokenHash())) {
            log.error("RT 재생성 해시 불일치 — 서명 키 교체 또는 재료 손상 의심. refreshTokenId={}", replacement.getId());
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return regenerated;
    }

    /**
     * 로그아웃 — RT가 존재하고 AT 유저의 소유일 때만 폐기하며,
     * 미존재·타인 소유는 조용히 무시한다(존재 여부 비노출). replaced_by는 NULL로 남아
     * 이후 재사용 시 Grace Period 없이 거부된다.
     */
    @Transactional
    public void logout(Long userId, String refreshToken) {
        authRepositoryService.findOwnedByTokenHash(userId, TokenHasher.sha256Hex(refreshToken))
                .ifPresent(rt -> rt.revoke(clock.instant()));
    }
}
