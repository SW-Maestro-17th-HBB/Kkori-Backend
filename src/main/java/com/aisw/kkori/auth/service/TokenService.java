package com.aisw.kkori.auth.service;

import com.aisw.kkori.auth.domain.RefreshToken;
import com.aisw.kkori.auth.dto.KakaoLoginResponse;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.auth.repository.RefreshTokenRepository;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.jwt.JwtProperties;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.global.jwt.TokenHasher;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.repository.UserRepository;
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

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    /** 카카오 신원으로 신규/기존/복구를 판정한다. 복구와 토큰 발급은 이 한 트랜잭션으로 묶인다. */
    @Transactional
    public KakaoLoginResponse processKakaoLogin(KakaoUserInfo info) {
        return userRepository.findByProviderId(info.providerId())
                .map(user -> {
                    boolean restored = user.isDeleted();
                    if (restored) {
                        // TODO(HBB1-10): deletion_log CANCELLED 전환·동의 AGREED 재기록·유예 기간 검증
                        user.restore();
                    }
                    return KakaoLoginResponse.loggedIn(restored, issueTokenPair(user.getId()));
                })
                .orElseGet(() -> KakaoLoginResponse.newUser(
                        jwtTokenProvider.createSignupToken(info.providerId(), info.email(), info.nickname())));
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
        refreshTokenRepository.save(
                RefreshToken.issue(userId, TokenHasher.sha256Hex(refreshToken), jti, issuedAt, expiresAt));

        return new TokenResponse(jwtTokenProvider.createAccessToken(userId), refreshToken);
    }

    /**
     * RTR 재발급. 전달된 RT의 해시 조회가 유일한 검증이며 JWT 파싱은 하지 않는다
     *
     * 검사 순서: 미존재 → 폐기(재사용/Grace) → 만료 → 회전. 폐기를 만료보다 먼저 보는 이유는
     * 회전된 뒤 만료된 토큰도 재사용 신호를 담고 있기 때문이다.
     *
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse reissue(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.RT_NOT_FOUND);
        }
        RefreshToken current = refreshTokenRepository.findWithLockByTokenHash(TokenHasher.sha256Hex(refreshToken))
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
            RefreshToken replacement = refreshTokenRepository
                    .findByTokenHash(current.getReplacedByTokenHash())
                    .filter(rt -> !rt.isRevoked() && !rt.isExpired(now))
                    .orElse(null);
            if (replacement != null) {
                return new TokenResponse(
                        jwtTokenProvider.createAccessToken(replacement.getUserId()),
                        regenerate(replacement));
            }
            throw new BusinessException(ErrorCode.RT_NOT_FOUND);
        }

        refreshTokenRepository.revokeAllByUserId(current.getUserId(), now);
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
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(refreshToken))
                .filter(rt -> rt.getUserId().equals(userId))
                .ifPresent(rt -> rt.revoke(clock.instant()));
    }
}
