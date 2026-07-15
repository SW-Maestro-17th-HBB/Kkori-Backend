package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * 조건부 soft delete — 활성 상태일 때만 {@code deleted_at}을 기록한다.
     * 영향 행 수(0/1)로 상태 전이의 수행 주체를 결정해 동일 유저의 탈퇴 처리를
     * 직렬화한다(PRD 기능 3). 벌크 쿼리라 영속성 컨텍스트를 우회하므로
     * 실행 후 컨텍스트를 비워 이후 조회가 DB 최신 상태를 읽게 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.deletedAt = :now where u.id = :userId and u.deletedAt is null")
    int softDeleteById(@Param("userId") Long userId, @Param("now") Instant now);
}
