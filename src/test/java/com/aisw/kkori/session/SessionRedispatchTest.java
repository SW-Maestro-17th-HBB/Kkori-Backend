package com.aisw.kkori.session;

import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.RoomPresence;
import com.aisw.kkori.session.dto.SessionWebhookSignal;
import com.aisw.kkori.session.service.DispatchMetadataAssembler;
import com.aisw.kkori.session.service.SessionEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 에이전트 재디스패치 파이프라인 검증 (PRD interview-session-reconnection.md 기능 3).
 *
 * <p>트리거는 실시간 판별 ③(webhook left(agent))의 AGENT_LOST 전이다 — 이벤트 서비스로
 * 전이를 일으켜 사전 확인 → CAS → list/delete → 재확인 → 상태 재확인 → create의 순서와
 * 각 관문의 차단을 검증한다.
 */
class SessionRedispatchTest extends SessionCompletionTestSupport {

    @Autowired
    private SessionEventService eventService;

    @Autowired
    private DispatchMetadataAssembler metadataAssembler;

    private void agentLeft(String room) {
        eventService.handle(new SessionWebhookSignal(SessionWebhookSignal.Type.AGENT_LEFT, room, "test-event"));
    }

    private String candidateOf(long sessionId) {
        return "candidate-" + sessionId;
    }

    @Test
    @DisplayName("판별 ③ 후 부재 확인 관통 시 재디스패치된다 — 재조립 metadata 자구 동일, CAS 기록")
    void redispatchesAfterArbitration() {
        long userId = saveUser("kakao-rd-1");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-rd-1");
        when(roomManager.probeRoomPresence("room-rd-1", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(false, false, null));
        when(agentDispatcher.listDispatchIds("room-rd-1")).thenReturn(List.of());

        agentLeft("room-rd-1");

        assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");
        assertThat(sessionInstant(sessionId, "redispatched_at")).isNotNull();
        // 재조립 계약 — 최초 디스패치와 같은 조립기의 산출물이 그대로 전달된다 (list 공집합 → delete 생략)
        String expected = metadataAssembler.assemble(sessionId,
                com.aisw.kkori.session.domain.InterviewType.THIRTY_MIN,
                com.aisw.kkori.session.domain.Position.BACKEND, null);
        verify(agentDispatcher).dispatch("room-rd-1", expected);
        verify(agentDispatcher, never()).deleteDispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("잔존 dispatch가 있으면 전건 삭제 후 생성한다 (단일성 계약 순서)")
    void deletesLeftoverDispatchesBeforeCreate() {
        long userId = saveUser("kakao-rd-2");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-rd-2");
        when(roomManager.probeRoomPresence("room-rd-2", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(false, false, null));
        when(agentDispatcher.listDispatchIds("room-rd-2")).thenReturn(List.of("dispatch-old"));

        agentLeft("room-rd-2");

        verify(agentDispatcher).deleteDispatch("room-rd-2", "dispatch-old");
        verify(agentDispatcher).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("사전 확인 — AGENT+candidate 관측 시 CAS 없이 ACTIVE 복원, dispatch 불변 (기회 비소진)")
    void preCheckRestoresActiveWithoutConsumingCas() {
        long userId = saveUser("kakao-rd-3");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-rd-3");
        Instant startedAt = Instant.parse("2026-08-01T08:00:00Z");
        setSessionInstant(sessionId, "started_at", startedAt);
        when(roomManager.probeRoomPresence("room-rd-3", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(true, true, null));

        agentLeft("room-rd-3");

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "redispatched_at")).isNull();
        assertThat(sessionInstant(sessionId, "started_at")).isEqualTo(startedAt);
        verify(agentDispatcher, never()).listDispatchIds(anyString());
        verify(agentDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("사전 확인 — AGENT만 관측 시 INTERRUPTED 전환(창 개시), disconnected_at 기록")
    void preCheckMovesToInterruptedWhenCandidateAbsent() {
        long userId = saveUser("kakao-rd-4");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-rd-4");
        when(roomManager.probeRoomPresence("room-rd-4", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(true, false, null));

        agentLeft("room-rd-4");

        assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
        assertThat(sessionInstant(sessionId, "disconnected_at")).isNotNull();
        assertThat(sessionInstant(sessionId, "redispatched_at")).isNull();
        verify(agentDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("삭제 후 부재 재확인 — AGENT 재출현 시 복원·생성 모두 포기 (종료 중 구 잡 모호성)")
    void postDeleteRecheckAbandonsOnAgentReappearance() {
        long userId = saveUser("kakao-rd-5");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-rd-5");
        when(roomManager.probeRoomPresence("room-rd-5", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(false, false, null))   // 사전 확인 — 부재
                .thenReturn(RoomPresence.of(true, false, null));   // 재확인 — 재출현
        when(agentDispatcher.listDispatchIds("room-rd-5")).thenReturn(List.of("dispatch-old"));

        agentLeft("room-rd-5");

        assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");
        assertThat(sessionInstant(sessionId, "redispatched_at")).isNotNull();
        verify(agentDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("생성 직전 상태 재확인 — 그 사이 terminal이 되면 create가 차단된다 (/end 경합)")
    void statusRecheckBlocksCreateAfterConcurrentEnd() {
        long userId = saveUser("kakao-rd-6");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-rd-6");
        when(roomManager.probeRoomPresence("room-rd-6", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(false, false, null))
                .thenAnswer(invocation -> {
                    // 부재 재확인 시점에 /end의 ABORTED 확정을 끼워 넣는 결정적 재현
                    jdbcTemplate.update("UPDATE interview_session SET status = 'ABORTED', ended_at = now() WHERE id = ?",
                            sessionId);
                    return RoomPresence.of(false, false, null);
                });
        when(agentDispatcher.listDispatchIds("room-rd-6")).thenReturn(List.of());

        agentLeft("room-rd-6");

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(agentDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("CAS 소진 세션은 재디스패치하지 않는다 — 재소실 시 유예 수렴 (생애 최대 1회)")
    void consumedCasBlocksSecondRedispatch() {
        long userId = saveUser("kakao-rd-7");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-rd-7");
        setSessionInstant(sessionId, "redispatched_at", Instant.parse("2026-08-01T09:00:00Z"));
        when(roomManager.probeRoomPresence("room-rd-7", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(false, false, null));

        agentLeft("room-rd-7");

        assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");
        verify(agentDispatcher, never()).listDispatchIds(anyString());
        verify(agentDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("사전 확인 실패(UNKNOWN)는 시도 중단 — CAS 미소진, 유예 수렴")
    void unknownPreCheckAbortsAttempt() {
        long userId = saveUser("kakao-rd-8");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-rd-8");
        when(roomManager.probeRoomPresence("room-rd-8", candidateOf(sessionId)))
                .thenReturn(RoomPresence.unknown());

        agentLeft("room-rd-8");

        assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");
        assertThat(sessionInstant(sessionId, "redispatched_at")).isNull();
        verify(agentDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("joined(agent) × AGENT_LOST — AGENT 실존+candidate 재실이면 ACTIVE, 부재면 INTERRUPTED, 룸 AGENT 부재면 no-op")
    void agentJoinedRecoversByObservation() {
        long userId = saveUser("kakao-rd-9");
        long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-rd-9");
        Instant disconnectedAt = Instant.parse("2026-08-01T10:00:00Z");
        setSessionInstant(sessionId, "disconnected_at", disconnectedAt);

        // 지연·중복 joined — 룸에 AGENT 없음 → no-op
        when(roomManager.probeRoomPresence("room-rd-9", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(false, true, null));
        eventService.handle(new SessionWebhookSignal(SessionWebhookSignal.Type.AGENT_JOINED, "room-rd-9", "t"));
        assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");

        // 복귀 — AGENT 실존 + candidate 부재 → INTERRUPTED(disconnected_at 보존)
        when(roomManager.probeRoomPresence("room-rd-9", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(true, false, null));
        eventService.handle(new SessionWebhookSignal(SessionWebhookSignal.Type.AGENT_JOINED, "room-rd-9", "t"));
        assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
        assertThat(sessionInstant(sessionId, "disconnected_at")).isEqualTo(disconnectedAt);
    }

    @Test
    @DisplayName("joined(agent) × AGENT_LOST — candidate 재실 복귀는 started_at 보존(null이면 현재 시각 기록)")
    void agentJoinedRestoresActivePreservingStartedAt() {
        long userId = saveUser("kakao-rd-10");
        long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-rd-10");
        when(roomManager.probeRoomPresence("room-rd-10", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(true, true, null));

        eventService.handle(new SessionWebhookSignal(SessionWebhookSignal.Type.AGENT_JOINED, "room-rd-10", "t"));

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "started_at")).isNotNull(); // PENDING발 — 현재 시각 기록
        assertThat(sessionInstant(sessionId, "disconnected_at")).isNull();
    }
}
