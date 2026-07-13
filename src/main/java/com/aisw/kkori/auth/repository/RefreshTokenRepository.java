package com.aisw.kkori.auth.repository;

import com.aisw.kkori.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * 재발급용 조회. 비관적 잠금으로 동일 RT의 동시 재발급을 직렬화한다 —
     * 잠금 없이는 두 요청이 모두 회전을 수행해 replaced_by가 꼬인다.
     * 잠금이 있으면 뒤의 요청은 앞의 커밋을 기다린 뒤 Grace Period 경로로 합류한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rt from RefreshToken rt where rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findWithLockByTokenHash(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 재사용 탈취 감지·탈퇴 시 해당 유저의 유효 RT 전부를 폐기한다. */
    @Modifying
    @Query("update RefreshToken rt set rt.revokedAt = :now where rt.userId = :userId and rt.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}
