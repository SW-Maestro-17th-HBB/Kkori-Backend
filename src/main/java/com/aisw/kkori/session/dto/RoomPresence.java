package com.aisw.kkori.session.dto;

import java.time.Instant;

/**
 * 룸 참가자 통합 관측 결과 (PRD interview-session-reconnection.md — 대조 지점 공통 재료).
 *
 * <p>AGENT는 participant kind로, candidate는 identity(`candidate-{sessionId}`) 일치로
 * 판별한다. <b>candidate 단독 관측은 ACTIVE의 증거가 아니다</b>(전 대조 지점 공통 규칙) —
 * 판정은 호출측 몫이고 이 record는 관측 사실만 담는다. 룸 미존재는 둘 다 부재인 관측으로
 * 접힌다(진행 중 아님의 확정 증거). {@code observed=false}(조회 실패)만 판정 불가다.
 */
public record RoomPresence(boolean observed, boolean agentPresent, boolean candidatePresent,
                           Instant agentJoinedAt) {

    public static RoomPresence unknown() {
        return new RoomPresence(false, false, false, null);
    }

    public static RoomPresence of(boolean agentPresent, boolean candidatePresent, Instant agentJoinedAt) {
        return new RoomPresence(true, agentPresent, candidatePresent, agentJoinedAt);
    }

    /** candidate + AGENT 동시 관측 — ACTIVE 복원의 유일한 충족 조건. */
    public boolean bothPresent() {
        return observed && agentPresent && candidatePresent;
    }
}
