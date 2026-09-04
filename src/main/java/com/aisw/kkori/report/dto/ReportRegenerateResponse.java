package com.aisw.kkori.report.dto;

import com.aisw.kkori.report.domain.ReportStatus;

/**
 * 재생성 요청 응답 — PENDING 복귀를 응답으로 전달한다
 * (PENDING은 SSE로 push하지 않으므로 이 응답이 유일한 통지 — PRD §5).
 */
public record ReportRegenerateResponse(
        Long reportId,
        ReportStatus status
) {
}
