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

    /** 잠금 없는 해시 조회 — 로그아웃과 Grace Period의 대체 토큰 조회에 사용한다. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * userId만 스칼라로 조회한다 — 재발급이 user 행 잠금 전에 대상 유저를 알아내는 용도.
     * 엔티티 조회를 쓰면 RT가 영속성 컨텍스트에 적재되어, 이후 잠금 조회가 그 사이
     * 회전된 최신 상태 대신 낡은 관리 인스턴스를 반환할 수 있다(이중 회전 위험).
     */
    @Query("select rt.userId from RefreshToken rt where rt.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 재사용 탈취 감지·탈퇴 시 해당 유저의 유효 RT 전부를 폐기한다.
     * 벌크 UPDATE는 영속성 컨텍스트를 우회하므로 실행 후 컨텍스트를 비운다 —
     * 현재 호출부엔 사전 적재된 RT가 없지만, 이후 추가될 호출부가
     * 낡은 관리 인스턴스를 읽는 부류의 버그를 예방한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken rt set rt.revokedAt = :now where rt.userId = :userId and rt.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}
