package com.aisw.kkori.report.repository;

import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 목록 조회 — status가 null이면 전체. 정렬은 Pageable의 Sort로 전달한다(createdAt 계열 전용). */
    @Query("select r from Report r where r.userId = :userId and (:status is null or r.status = :status)")
    Page<Report> findPage(@Param("userId") Long userId, @Param("status") ReportStatus status,
                          Pageable pageable);

    /**
     * overallScore 내림차순 — 미완성 리포트의 점수(null)는 정렬 방향과 무관하게 항상 뒤로 보내고,
     * 동점·동시각은 생성 시각 내림차순 → id 내림차순으로 고정한다(페이지 순서 흔들림 방지, PRD §2).
     * null을 뒤로 보내는 조건은 Pageable의 Sort로 표현할 수 없어 쿼리에 직접 둔다 — Pageable은 정렬 없이 전달할 것.
     */
    @Query("select r from Report r where r.userId = :userId and (:status is null or r.status = :status) "
            + "order by r.overallScore desc nulls last, r.createdAt desc, r.id desc")
    Page<Report> findPageOrderByOverallScoreDesc(@Param("userId") Long userId,
                                                 @Param("status") ReportStatus status,
                                                 Pageable pageable);

    /** overallScore 오름차순 — null은 여기서도 항상 뒤. */
    @Query("select r from Report r where r.userId = :userId and (:status is null or r.status = :status) "
            + "order by r.overallScore asc nulls last, r.createdAt desc, r.id desc")
    Page<Report> findPageOrderByOverallScoreAsc(@Param("userId") Long userId,
                                                @Param("status") ReportStatus status,
                                                Pageable pageable);
}
