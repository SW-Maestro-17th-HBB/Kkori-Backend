package com.aisw.kkori.report.repository;

import com.aisw.kkori.report.domain.ReportScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReportScoreRepository extends JpaRepository<ReportScore, Long> {

    Optional<ReportScore> findByReportId(Long reportId);

    /** 통계의 축별 평균용 일괄 조회 (PRD §6). */
    List<ReportScore> findByReportIdIn(Collection<Long> reportIds);
}
