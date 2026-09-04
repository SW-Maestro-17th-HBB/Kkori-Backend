package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 동의 이력 저장소. append-only라 저장 외 조작이 없다. */
public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    /** 유저의 동의 이력 전체. 이력 감사·테스트 검증용 — 최신 상태 판정은 {@link #findLatestByUserId} 사용. */
    List<UserConsent> findByUserId(Long userId);

    /**
     * 유형별 최신 행만 반환한다(최신 = 유형별 max(id), 결과 최대 4행).
     *
     * <p>append-only로 이력이 단조 증가하므로 전체 로딩 후 애플리케이션 추림 대신 DB에서
     * 끝낸다(조회·변경 API 100ms 요구 — PRD consent.md). 이 쿼리가 보장하는 것은 결과 행과
     * 엔티티화가 최대 4건이라는 상한이며, 집계 비용 자체는 유저 이력량에 비례한다
     * (현 인덱스는 user_id 단독) — 측정이 100ms를 넘길 때만 복합 인덱스를 검토한다.
     */
    @Query("""
            select uc from UserConsent uc
            where uc.userId = :userId and uc.id in (
                select max(uc2.id) from UserConsent uc2
                where uc2.userId = :userId group by uc2.consentType)""")
    List<UserConsent> findLatestByUserId(@Param("userId") Long userId);
}
