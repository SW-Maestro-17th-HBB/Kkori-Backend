package com.aisw.kkori.session.dto;

/**
 * 검증된 webhook 이벤트의 벤더 무관 표현 (PRD interview-session-completion.md ·
 * interview-session-reconnection.md — 이벤트→전이 매핑).
 *
 * <p>어댑터가 LiveKit 이벤트·participant kind·disconnect reason을 도메인 신호로 접어 전달한다:
 * {@code participant_connection_aborted}(AGENT)는 {@code AGENT_LEFT}와 동일 취급(판별 3-경로),
 * candidate(비AGENT)의 {@code participant_left}는 reason={@code DUPLICATE_IDENTITY}(동일 identity
 * 재입장이 걷어찬 유령 연결)를 어댑터가 IGNORE로 접는다 — 전이 재료가 아니다.
 * candidate의 {@code connection_aborted}(미입장 사건)·미구독 이벤트는 {@code IGNORE}.
 * {@code rawEvent}는 로그 전용이다.
 */
public record SessionWebhookSignal(Type type, String roomName, String rawEvent) {

    public enum Type {
        /** participant_joined (kind=AGENT) → PENDING→ACTIVE, AGENT_LOST는 대조 복귀(재연결 문서). */
        AGENT_JOINED,
        /** participant_left·participant_connection_aborted (kind=AGENT) → 판별 3-경로. */
        AGENT_LEFT,
        /** participant_joined (candidate) → INTERRUPTED면 대조 복귀, 그 외 no-op. */
        CANDIDATE_JOINED,
        /** participant_left (candidate, 유령 퇴장 제외) → ACTIVE면 INTERRUPTED 전이. */
        CANDIDATE_LEFT,
        /** room_finished → 행 판별 terminal 확정. */
        ROOM_FINISHED,
        /** 그 외 전부 — 전이 없음. */
        IGNORE
    }

    public static SessionWebhookSignal ignore(String rawEvent) {
        return new SessionWebhookSignal(Type.IGNORE, null, rawEvent);
    }
}
