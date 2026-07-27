package com.aisw.kkori.report.dto;

import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.domain.WeaknessTagCount;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;

/**
 * 리포트 목록 응답 (docs/requirements/report/report.md §2).
 *
 * <p>목록 항목은 REPORTS 단독으로 구성한다(조인 없음) — 스냅샷 비정규화가 이를 위한 설계.
 * 생성 중·실패 리포트도 노출되며, 미완성 리포트의 점수·태그 요약은 null이다.
 */
public record ReportListResponse(
        List<ReportListItem> reports,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {

    public record ReportListItem(
            Long reportId,
            ReportStatus status,
            Integer overallScore,
            String resumeFileName,
            List<WeaknessTagCount> weaknessTagSummary,
            Instant createdAt,
            Instant completedAt
    ) {
        static ReportListItem from(Report report) {
            return new ReportListItem(
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

    public static ReportListResponse from(Page<Report> reportPage) {
        return new ReportListResponse(
                reportPage.getContent().stream().map(ReportListItem::from).toList(),
                reportPage.getNumber(),
                reportPage.getSize(),
                reportPage.getTotalElements(),
                reportPage.hasNext()
        );
    }
}
