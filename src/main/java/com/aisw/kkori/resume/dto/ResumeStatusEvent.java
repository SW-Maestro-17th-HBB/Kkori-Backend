package com.aisw.kkori.resume.dto;

/**
 * SSE로 push되는 분석 상태 이벤트 data (docs/requirements/resume.md §3 단일 스키마).
 *
 * <p>message는 status만으로 유도할 수 없는 정보(예: FAILED의 실패 사유) 전달용이며,
 * 정상 단계의 표시 문구·진행률은 프론트가 status 기반으로 매핑한다.
 */
public record ResumeStatusEvent(
        Long resumeId,
        String status,
        String message
) {
}
