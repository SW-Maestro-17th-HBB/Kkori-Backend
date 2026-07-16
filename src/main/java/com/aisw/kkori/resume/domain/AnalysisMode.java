package com.aisw.kkori.resume.domain;

/**
 * 분석 요청 모드 (docs/requirements/resume/resume.md §4).
 *
 * <p>사용자가 고르는 값이 아니라 서버가 상태로 결정한다 — 신규 업로드·FAILED 복구는 FULL,
 * EMBEDDED에서의 수정 반영은 REINDEX.
 */
public enum AnalysisMode {

    /** S3 원본 PDF부터 전체 파이프라인 재수행 (텍스트 추출 → 구조화 → 청킹 → 임베딩 → 색인). */
    FULL,

    /**
     * DB의 structured_data(사용자 수정본)를 입력으로 청킹 → 임베딩 → 색인만 재수행.
     * 구조화를 재수행하면 LLM이 사용자 수정을 덮어쓰므로 건너뛴다. bucket/objectKey는 무시된다.
     */
    REINDEX,
}
