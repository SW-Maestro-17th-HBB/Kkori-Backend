package com.aisw.kkori.report.domain;

/**
 * 개선 과제 — REPORT_FEEDBACKS.improvement_tasks(jsonb) 요소의 계약 record.
 *
 * <p>스키마 정의 원천 (docs/requirements/report/report.md §1 인터페이스 요구사항).
 * Worker가 답변별 평가 시 생성하며, 상세 API는 답변별 과제를 질문 순서대로 모아 반환한다.
 */
public record ImprovementTask(
        String title,
        String description
) {
}
