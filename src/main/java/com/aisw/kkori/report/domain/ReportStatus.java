package com.aisw.kkori.report.domain;

/**
 * 리포트 생성 상태 (docs/requirements/report/report.md §Overview 상태 표).
 *
 * <p>상태의 진실 원천은 REPORTS.status이며, 생성 파이프라인의 전이는 Worker가 수행한다.
 * 단 하나의 예외로, FAILED 재생성 시 FAILED → PENDING 전환은 Spring(재생성 API)이 수행한다.
 * 조회 시에는 판정에만 사용한다 — COMPLETED 전에는 상세·타임라인을 열지 않는다.
 */
public enum ReportStatus {
    /** 세션 종료로 리포트가 생성되어 평가 대기 중 */
    PENDING,
    /** Worker가 평가 진행 중 (텍스트 분석 → 음성 분석 2단계 포함) */
    PROCESSING,
    /** 평가 완료 — 조회 가능한 최종 상태 */
    COMPLETED,
    /** 생성 실패 — 복구는 사용자의 재생성 요청으로만 */
    FAILED
}
