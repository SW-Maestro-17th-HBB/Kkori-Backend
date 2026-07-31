package com.aisw.kkori.user.repository;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
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
     * user 행 잠금 + 활성 재확인 — "잠금 후 활성 재확인" 관용구의 단일 정의.
     * 유저 상태에 의존하는 쓰기 경로(정보 수정·동의 변경·세션 생성·이력서 수정/재분석)가
     * 공유하는 직렬화 지점으로, 탈퇴가 선점했으면 {@code UNAUTHORIZED}를 던진다.
     * 의도적으로 다른 변형(탈퇴의 무필터 멱등 잠금, 재발급의 RT_NOT_FOUND)은 이 헬퍼를 쓰지 않는다.
     */
    default User findActiveWithLock(Long id) {
        return findWithLockById(id)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

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
