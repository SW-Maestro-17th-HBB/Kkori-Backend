package com.aisw.kkori.report.dto;

/**
 * SSE로 push되는 리포트 생성 상태 이벤트 data (docs/requirements/report/report.md §5 단일 스키마).
 *
 * <p>message는 status만으로 유도할 수 없는 정보(예: FAILED의 실패 사유) 전달용이며,
 * 표시 문구는 프론트가 status 기반으로 매핑한다.
 */
public record ReportStatusEvent(
        Long reportId,
        String status,
        String message
) {
}
