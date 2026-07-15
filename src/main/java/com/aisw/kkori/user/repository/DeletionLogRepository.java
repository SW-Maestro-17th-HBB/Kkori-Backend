package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.DeletionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeletionLogRepository extends JpaRepository<DeletionLog, Long> {

    /**
     * 유저의 최신 탈퇴 요청 — 카카오 로그인의 상태별 판정 재료(PRD 기능 4).
     * 정렬을 명시하지 않은 findFirst는 반환 로그가 비결정적이므로 금지.
     */
    Optional<DeletionLog> findFirstByUserIdOrderByRequestedAtDescIdDesc(Long userId);
}
