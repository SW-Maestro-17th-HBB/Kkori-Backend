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

    /** {@link RefreshTokenRepository#findWithLockByTokenHash} 위임. */
    public Optional<RefreshToken> findWithLockByTokenHash(String tokenHash) {
        return refreshTokenRepository.findWithLockByTokenHash(tokenHash);
    }

    /** {@link RefreshTokenRepository#findByTokenHash} 위임. */
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return refreshTokenRepository.findByTokenHash(tokenHash);
    }

    /** {@link RefreshTokenRepository#findUserIdByTokenHash} 위임. */
    public Optional<Long> findUserIdByTokenHash(String tokenHash) {
        return refreshTokenRepository.findUserIdByTokenHash(tokenHash);
    }

    /** {@link RefreshTokenRepository#revokeAllByUserId} 위임 — 재사용 탈취 감지와 탈퇴가 공용. */
    public int revokeAllByUserId(Long userId, Instant now) {
        return refreshTokenRepository.revokeAllByUserId(userId, now);
    }
}
