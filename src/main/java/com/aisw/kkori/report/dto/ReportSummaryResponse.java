package com.aisw.kkori.report.dto;

import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.domain.WeaknessTagCount;

import java.time.Instant;
import java.util.List;

/**
 * 리포트 목록 항목 (docs/requirements/report/report.md §2).
 *
 * <p>REPORTS 단독으로 구성한다(조인 없음) — 스냅샷 비정규화가 이를 위한 설계.
 * 미완성(PENDING/PROCESSING/FAILED) 리포트의 점수·태그 요약은 null이다.
 * 페이지 엔벨로프는 공용 {@link com.aisw.kkori.global.response.PageResponse}를 쓴다.
 */
public record ReportSummaryResponse(
        Long reportId,
        ReportStatus status,
        Integer overallScore,
        String resumeFileName,
        List<WeaknessTagCount> weaknessTagSummary,
        Instant createdAt,
        Instant completedAt
) {

    public static ReportSummaryResponse from(Report report) {
        return new ReportSummaryResponse(
                report.getId(),
                report.getStatus(),
                report.getOverallScore(),
                report.getResumeFileNameSnapshot(),
                report.getWeaknessTagSummary(),
                report.getCreatedAt(),
                report.getCompletedAt()
        );
    }
}
