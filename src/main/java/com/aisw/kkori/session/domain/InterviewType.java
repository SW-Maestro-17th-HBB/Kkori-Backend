package com.aisw.kkori.session.domain;

/**
 * 면접 유형 — 세션 인프라는 유형 무관 공유, 유형별 차이는 Agent 질문 파이프라인 소관.
 *
 * <p>이름은 사용자에게 노출되는 구분(면접 길이)을 따른다. 서버는 값 검증·저장·전달만 하고
 * 의미를 해석하지 않는다.
 */
public enum InterviewType {

    /** 30분 — 이력서 기반 개인 맞춤 면접 (RAG·경험 심화). resumeId 필수. */
    THIRTY_MIN,
    /** 5분 — CS 지식 위주 면접 (정답 있는 질문, 별도 평가). resumeId 선택. */
    FIVE_MIN;

    /** 이 유형이 이력서를 반드시 요구하는지 — 유형별 검증 분기의 유일한 지점. */
    public boolean requiresResume() {
        return this == THIRTY_MIN;
    }
}
