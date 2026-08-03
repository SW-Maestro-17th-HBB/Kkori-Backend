package com.aisw.kkori.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 대본(INTERVIEW_TRANSCRIPT content JSON)의 발화 1개 — 리포트 도메인의 읽기 전용 뷰.
 *
 * <p>스키마의 소유는 면접 도메인·에이전트다(PRD §1 기타 대본 계약). 리포트는 소비자로서
 * 필요한 필드만 읽고, 모르는 필드는 무시한다(계약 확장 허용). speaker·questionType은
 * 값 집합 확정 전이므로 문자열로 받아 그대로 전달한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranscriptUtterance(
        Integer questionNumber,
        Integer parentQuestionNumber,
        String speaker,
        String questionType,
        String content,
        String spokenAt
) {
    public static final String SPEAKER_INTERVIEWER = "INTERVIEWER";
    public static final String SPEAKER_USER = "USER";
}
