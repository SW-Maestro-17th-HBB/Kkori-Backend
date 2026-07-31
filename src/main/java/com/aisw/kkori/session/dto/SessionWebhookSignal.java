package com.aisw.kkori.session.dto;

/**
 * 검증된 webhook 이벤트의 벤더 무관 표현 (PRD interview-session-completion.md — 이벤트→전이 매핑).
 *
 * <p>어댑터가 LiveKit 이벤트·participant kind를 도메인 신호로 접어 전달한다:
 * {@code participant_connection_aborted}(AGENT)는 {@code AGENT_LEFT}와 동일 취급(판별 3-경로),
 * 비AGENT participant 이벤트·미구독 이벤트는 {@code IGNORE}. {@code rawEvent}는 로그 전용이다.
 */
public record SessionWebhookSignal(Type type, String roomName, String rawEvent) {

    public enum Type {
        /** participant_joined (kind=AGENT) → PENDING→ACTIVE. */
        AGENT_JOINED,
        /** participant_left·participant_connection_aborted (kind=AGENT) → 판별 3-경로. */
        AGENT_LEFT,
        /** room_finished → 행 판별 terminal 확정. */
        ROOM_FINISHED,
        /** 그 외 전부 — 전이 없음. */
        IGNORE
    }

    public static SessionWebhookSignal ignore(String rawEvent) {
        return new SessionWebhookSignal(Type.IGNORE, null, rawEvent);
    }
}
