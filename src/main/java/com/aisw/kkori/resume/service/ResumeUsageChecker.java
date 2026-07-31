package com.aisw.kkori.resume.service;

/**
 * "이 이력서가 진행 중인 면접에서 사용 중인가"의 판정 추상 (resume PRD §4·§5 — RESUME_IN_USE).
 *
 * <p>판정 근거(non-terminal 면접 세션의 존재)는 세션 도메인 소관이므로 구현은 세션 쪽에 있다
 * ({@code session.service.InterviewSessionResumeUsageChecker}). resume가 session 패키지를
 * 직접 참조하면 세션→resume(접근 가드) 의존과 함께 양방향 순환이 생기므로, 이 포트로
 * 의존 방향을 한쪽(session→resume)으로 유지한다 — LiveKit 어댑터 격리와 동일한 구조.
 */
public interface ResumeUsageChecker {

    boolean isInUse(Long resumeId);
}
