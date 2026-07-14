package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 카카오 회원번호로 조회. soft delete된 유저도 반환한다 —
     * 로그인 시 신규/기존/복구 판정과 인증 필터의 탈퇴 검증에 필요하다.
     */
    Optional<User> findByProviderId(String providerId);
}
