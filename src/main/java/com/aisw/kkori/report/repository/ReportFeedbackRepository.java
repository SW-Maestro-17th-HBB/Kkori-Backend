package com.aisw.kkori.report.repository;

import com.aisw.kkori.report.domain.ReportFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportFeedbackRepository extends JpaRepository<ReportFeedback, Long> {

    List<ReportFeedback> findByReportIdOrderByQuestionNumberAsc(Long reportId);
}
