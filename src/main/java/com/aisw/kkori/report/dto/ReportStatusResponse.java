package com.aisw.kkori.report.dto;

import com.aisw.kkori.report.domain.Report;

import java.time.Instant;

/**
 * 생성 상태 REST 조회 응답 (docs/requirements/report/report.md §5).
 *
 * <p>SSE 유실·재연결 시 동기화용 — 모든 상태에서 조회 가능하다.
 * failedReason은 FAILED가 아니면 null이다.
 */
public record ReportStatusResponse(
        Long reportId,
        String status,
        String failedReason,
        Instant createdAt,
        Instant completedAt
) {

    public static ReportStatusResponse from(Report report) {
        return new ReportStatusResponse(
                report.getId(),
                report.getStatus().name(),
                report.getFailedReason(),
                report.getCreatedAt(),
                report.getCompletedAt()
        );
    }
}
