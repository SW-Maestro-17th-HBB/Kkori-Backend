package com.aisw.kkori.report.repository;

import com.aisw.kkori.report.domain.ReportScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReportScoreRepository extends JpaRepository<ReportScore, Long> {

    Optional<ReportScore> findByReportId(Long reportId);
}
