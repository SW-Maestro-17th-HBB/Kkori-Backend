package com.aisw.kkori.report.domain;

/**
 * 약점 태그 빈도 — REPORTS.weakness_tag_summary(jsonb) 요소의 계약 record.
 *
 * <p>스키마 정의 원천 (docs/requirements/report/report.md §1 인터페이스 요구사항).
 * Worker가 답변별 태그 빈도 상위 3개를 계산해 저장하고, 백엔드는 태그 코드를
 * 불투명 문자열로 취급한다(어휘집은 Worker PRD 소관).
 */
public record WeaknessTagCount(
        String tag,
        int count
) {
}
