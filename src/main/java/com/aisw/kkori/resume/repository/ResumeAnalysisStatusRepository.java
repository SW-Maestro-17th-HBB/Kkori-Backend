package com.aisw.kkori.resume.repository;

import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.dto.ResumeSummaryResponse;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ResumeAnalysisStatusRepository extends JpaRepository<ResumeAnalysisStatus, Long> {

    Optional<ResumeAnalysisStatus> findByResumeId(Long resumeId);

    /**
     * 이력서 목록 조회 (PRD §2) — 상태 테이블을 루트로 Resume를 join해 목록 DTO를 한 쿼리로 만든다
     * (이력서마다 상태를 따로 읽는 N+1 방지). 업로드가 상태 row를 같은 트랜잭션에서 생성하므로(1:1 보장)
     * join으로 누락되는 이력서는 없고, soft delete 제외는 Resume의 @SQLRestriction이 join 조건에 얹힌다.
     * 정렬은 createdAt 내림차순 고정, 동시각 대비 id 내림차순 tiebreak.
     */
    @Query("""
            select new com.aisw.kkori.resume.dto.ResumeSummaryResponse(
                r.id, r.title, s.parseStatus, r.createdAt, r.fileSize)
            from ResumeAnalysisStatus s join s.resume r
            where r.userId = :userId
            order by r.createdAt desc, r.id desc
            """)
    Page<ResumeSummaryResponse> findSummariesByUserId(Long userId, Pageable pageable);

    /** {@link #findSummariesByUserId}의 상태 필터 버전 — null 파라미터 분기 JPQL 대신 메서드를 나눠 쿼리를 단순하게 유지. */
    @Query("""
            select new com.aisw.kkori.resume.dto.ResumeSummaryResponse(
                r.id, r.title, s.parseStatus, r.createdAt, r.fileSize)
            from ResumeAnalysisStatus s join s.resume r
            where r.userId = :userId and s.parseStatus = :status
            order by r.createdAt desc, r.id desc
            """)
    Page<ResumeSummaryResponse> findSummariesByUserIdAndStatus(Long userId, AnalysisStatus status, Pageable pageable);

    /**
     * 상태 row를 잠그고 읽는다(SELECT ... FOR UPDATE) — 상태 확인 후 행동하는 트랜잭션(수정·재분석)끼리
     * 직렬화해 낡은 판정으로 통과하는 경합을 막는다. 락은 이름이 아니라 {@code @Lock}이 건다 —
     * ForUpdate는 서술일 뿐이므로 애너테이션을 지우면 이름만 남고 락은 조용히 사라진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ResumeAnalysisStatus> findForUpdateByResumeId(Long resumeId);
}
