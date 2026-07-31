package com.aisw.kkori.session.domain;

import java.util.Set;

/**
 * 면접 세션 상태 (PRD: docs/requirements/session/interview-session-creation.md — 세션 상태).
 *
 * <p>본 스토리에서 실제로 발생하는 상태는 {@code PENDING}(생성)과 {@code ABORTED}(기존
 * PENDING 자동 교체)뿐이다. 나머지 전이(webhook 기반 ACTIVE 전환, 유예·종료 처리)는
 * 후속 스토리가 도입하며, 값 집합은 상태 머신 전체를 지금 확정해 계약 변경을 막는다.
 */
public enum SessionStatus {

    /** 룸·토큰 발급 완료, Agent 접속 전 (candidate는 먼저 입장해 있을 수 있음). */
    PENDING,
    /** Agent 접속 완료, 면접 진행 중. */
    ACTIVE,
    /** candidate 연결 끊김, 재연결 대기. */
    INTERRUPTED,
    /** Agent 이탈, 재dispatch 대기. */
    AGENT_LOST,
    /** 정상 종료 (terminal). */
    ENDED,
    /** 비정상 종료 (terminal). */
    ABORTED;

    /** terminal이 아닌 모든 상태 — "진행 중 세션" 판정(이력서 사용 중 검사 포함)의 기준. */
    public static final Set<SessionStatus> NON_TERMINAL = Set.of(PENDING, ACTIVE, INTERRUPTED, AGENT_LOST);

    /** 실시간 면접이 살아 있는 상태 — 생성 요청을 409로 거부하는 기준 (PENDING은 자동 교체 대상). */
    public static final Set<SessionStatus> IN_PROGRESS = Set.of(ACTIVE, INTERRUPTED, AGENT_LOST);

    public boolean isTerminal() {
        return this == ENDED || this == ABORTED;
    }
}
