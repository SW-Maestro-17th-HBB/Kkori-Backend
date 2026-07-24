package com.aisw.kkori.resume.dto;

import com.aisw.kkori.resume.domain.AnalysisStatus;

import java.time.Instant;

/**
 * 이력서 목록 항목 (PRD §2). UI가 소비하는 최소 필드만 내려준다 —
 * 분석 결과 미리보기는 목록에 싣지 않고 행 펼침 시 {@code GET /{resumeId}/parsed}로 조회한다.
 */
public record ResumeSummaryResponse(
        Long resumeId,
        String title,
        AnalysisStatus analysisStatus,
        Instant createdAt,
        Long fileSize
) {
}
