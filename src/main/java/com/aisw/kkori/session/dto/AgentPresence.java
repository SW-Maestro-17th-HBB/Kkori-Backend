package com.aisw.kkori.session.dto;

import java.time.Instant;

/**
 * 룸의 AGENT 참가자 관측 결과 (PRD interview-session-completion.md 기능 3 — stale PENDING 대조).
 *
 * <p>{@code ABSENT}는 "룸에 AGENT 없음"과 "룸 미존재"를 함께 뜻한다 — 둘 다 "진행 중 면접
 * 아님"의 확정 증거로 정리를 진행한다. {@code UNKNOWN}(조회 실패)만 이번 회차를 건너뛴다.
 */
public record AgentPresence(Status status, Instant joinedAt) {

    public enum Status { PRESENT, ABSENT, UNKNOWN }

    public static AgentPresence present(Instant joinedAt) {
        return new AgentPresence(Status.PRESENT, joinedAt);
    }

    public static AgentPresence absent() {
        return new AgentPresence(Status.ABSENT, null);
    }

    public static AgentPresence unknown() {
        return new AgentPresence(Status.UNKNOWN, null);
    }
}
