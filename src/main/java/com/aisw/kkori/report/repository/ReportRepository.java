package com.aisw.kkori.report.repository;

import com.aisw.kkori.report.domain.Report;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {
}
