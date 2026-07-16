package com.aisw.kkori.resume.dto;

import com.aisw.kkori.resume.domain.AnalysisStatus;

/** 재분석 요청 응답 — 재시작된 상태를 그대로 알려준다 (FULL→UPLOADED, REINDEX→EMBEDDING). */
public record ResumeReanalyzeResponse(
        Long resumeId,
        AnalysisStatus analysisStatus
) {
}
