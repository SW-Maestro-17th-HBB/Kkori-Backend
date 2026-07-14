package com.aisw.kkori.resume.domain;

/**
 * 이력서 분석 파이프라인 상태 (docs/requirements/resume.md §Overview).
 *
 * <p>{@code UPLOADED → PARSING → TEXT_EXTRACTING → STRUCTURING → PARSED → EMBEDDING → EMBEDDED},
 * 실패 시 {@code FAILED}. UPLOADED는 Spring이, 이후 전이는 Python AI Worker가 기록한다.
 */
public enum AnalysisStatus {
    UPLOADED,
    PARSING,
    TEXT_EXTRACTING,
    STRUCTURING,
    PARSED,
    EMBEDDING,
    EMBEDDED,
    FAILED,
}
