package com.aisw.kkori.resume.dto;

import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.StructuredData;

import java.time.Instant;

/** 파싱 결과 조회·수정 응답 (PRD §4). rawText는 계약상 내려주지 않는다 — 노출 범위는 구조화 결과뿐. */
public record ResumeParsedResponse(
        Long resumeId,
        AnalysisStatus analysisStatus,
        StructuredData structuredData,
        Instant updatedAt
) {

    public static ResumeParsedResponse of(Resume resume, AnalysisStatus status) {
        return new ResumeParsedResponse(
                resume.getId(), status, resume.getStructuredData(), resume.getUpdatedAt());
    }
}
