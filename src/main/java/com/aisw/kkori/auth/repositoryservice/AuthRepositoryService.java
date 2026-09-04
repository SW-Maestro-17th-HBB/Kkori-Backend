package com.aisw.kkori.auth.repositoryservice;

import com.aisw.kkori.auth.domain.RefreshToken;
import com.aisw.kkori.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * auth 도메인 영속성 접근 계층 — 현재는 RefreshToken만 감싼다.
 * service·타 도메인은 raw repository 대신 이 계층을 거친다(CLAUDE.md 패키지 구조 규칙).
 * 트랜잭션은 소유하지 않는다 — 잠금 메서드는 반드시 호출자의 트랜잭션 안에서 호출해야
 * 잠금이 트랜잭션 끝까지 유지된다.
 */
@Service
@RequiredArgsConstructor
public class AuthRepositoryService {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshToken save(RefreshToken refreshToken) {
        return refreshTokenRepository.save(refreshToken);
    }

    public Optional<RefreshToken> findWithLockByTokenHash(String tokenHash) {
        return refreshTokenRepository.findWithLockByTokenHash(tokenHash);
    }

    /** Grace Period의 대체 토큰 조회 — 폐기·만료된 토큰은 없는 것으로 취급한다. */
    public Optional<RefreshToken> findValidReplacement(String tokenHash, Instant now) {
        return refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(rt -> !rt.isRevoked() && !rt.isExpired(now));
    }

    /** 해시 조회 + 소유자 확인 — 타인 소유는 없는 것으로 취급한다(존재 여부 비노출, 로그아웃 경로). */
    public Optional<RefreshToken> findOwnedByTokenHash(Long userId, String tokenHash) {
        return refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(rt -> rt.getUserId().equals(userId));
    }

    public Optional<Long> findUserIdByTokenHash(String tokenHash) {
        return refreshTokenRepository.findUserIdByTokenHash(tokenHash);
    }

    public void revokeAllByUserId(Long userId, Instant now) {
        refreshTokenRepository.revokeAllByUserId(userId, now);
    }
}
