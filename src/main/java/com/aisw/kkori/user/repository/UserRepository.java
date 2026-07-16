package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 카카오 회원번호로 조회. soft delete된 유저도 반환한다 —
     * 로그인 시 신규/기존/복구 판정과 인증 필터의 탈퇴 검증에 필요하다.
     */
    Optional<User> findByProviderId(String providerId);

    /**
     * 카카오 회원번호로 id만 스칼라 조회한다 — 로그인이 user 행 잠금 전에 대상을 알아내는 용도.
     * 엔티티 조회를 쓰면 이후 잠금 조회가 낡은 관리 인스턴스를 반환할 수 있다.
     */
    @Query("select u.id from User u where u.providerId = :providerId")
    Optional<Long> findIdByProviderId(@Param("providerId") String providerId);

    /**
     * user 행 잠금 조회 — 유저 상태를 쓰는 경로(수정·토큰 재발급)의 직렬화 지점.
     * 잠금 없이 조회 후 flush하면 그 사이 커밋된 탈퇴의 {@code deleted_at}을
     * 조회 시점 값으로 되덮는 lost update가 발생할 수 있다(PRD 기능 2).
     * 잠금 순서는 user → RT로 통일한다(역전 시 탈퇴의 RT 전량 폐기가 새 RT를 놓침).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findWithLockById(Long id);

    /**
     * 조건부 soft delete — 활성 상태일 때만 {@code deleted_at}을 기록한다.
     * 영향 행 수(0/1)로 상태 전이의 수행 주체를 결정해 동일 유저의 탈퇴 처리를
     * 직렬화한다(PRD 기능 3). 벌크 쿼리라 영속성 컨텍스트를 우회하므로
     * 실행 후 컨텍스트를 비워 이후 조회가 DB 최신 상태를 읽게 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.deletedAt = :now where u.id = :userId and u.deletedAt is null")
    int softDeleteById(@Param("userId") Long userId, @Param("now") Instant now);
}
