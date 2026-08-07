package com.aisw.kkori.session;

import com.aisw.kkori.session.config.SessionProperties;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.AgentPresence;
import com.aisw.kkori.session.dto.RoomPresence;
import com.aisw.kkori.session.repository.InterviewTranscriptReader;
import com.aisw.kkori.session.service.SessionSweeper;
import com.aisw.kkori.session.service.SessionTransitionExecutor;
import com.aisw.kkori.session.service.TerminationMarkerReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 세션 수렴 스위퍼 검증 (PRD interview-session-completion.md 기능 2·3, 공통: 스위퍼·설정).
 *
 * <p>스위퍼를 미래 고정 Clock으로 직접 구성해 스캔 컷오프만 경과시킨다 — 실제 대기 없이
 * 만료·stale 조건을 재현하고, 전이 트랜잭션 자체는 컨텍스트의 실물 실행기(user 잠금)를 쓴다.
 */
class SessionSweeperTest extends SessionCompletionTestSupport {

    private static final Duration FALLBACK = Duration.ofSeconds(180);
    private static final Duration GRACE = Duration.ofSeconds(60);
    private static final Duration STALE = Duration.ofMinutes(45);
    private static final Duration WINDOW = Duration.ofMinutes(3);

    @Autowired
    private InterviewTranscriptReader transcriptReader;

    @Autowired
    private TerminationMarkerReader markerReader;

    @Autowired
    private SessionTransitionExecutor transitionExecutor;

    /** 모든 앵커(지금 기록)가 임계를 지난 것으로 보이는 미래 시점의 스위퍼. */
    private SessionSweeper sweeperAt(Instant now) {
        return new SessionSweeper(sessionRepository, transcriptReader, markerReader, roomManager,
                transitionExecutor, new SessionProperties(FALLBACK, GRACE, STALE, Duration.ofSeconds(10), WINDOW),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private SessionSweeper expiredSweeper() {
        return sweeperAt(Instant.now().plus(Duration.ofHours(2)));
    }

    private SessionSweeper freshSweeper() {
        return sweeperAt(Instant.now());
    }

    @Test
    @DisplayName("fallback — 행 있으면 ENDED 선기록 후 룸 삭제 (정상 종료의 fallback 경합 보호)")
    void fallbackRecordsEndedWithTranscript() {
        long userId = saveUser("kakao-sw-1");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-sw-1");
        setSessionInstant(sessionId, "end_requested_at", Instant.now());
        seedTranscript(sessionId);

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
        verify(roomManager).deleteRoomQuietly("room-sw-1");
    }

    @Test
    @DisplayName("fallback — 행 없으면 ABORTED 선기록 후 룸 삭제 (선기록 계약 순서)")
    void fallbackRecordsAbortedWithoutTranscript() {
        long userId = saveUser("kakao-sw-2");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-sw-2");
        setSessionInstant(sessionId, "end_requested_at", Instant.now());

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        assertThat(sessionInstant(sessionId, "ended_at")).isNotNull();
        verify(roomManager).deleteRoomQuietly("room-sw-2");
    }

    @Test
    @DisplayName("fallback — 타임아웃 미경과 세션은 건드리지 않는다")
    void fallbackLeavesUnexpired() {
        long userId = saveUser("kakao-sw-3");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-sw-3");
        setSessionInstant(sessionId, "end_requested_at", Instant.now());

        freshSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        verify(roomManager, never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("유예 만료 — AGENT_LOST가 ABORTED로 정리되고 룸이 삭제된다 (재생성 409 차단 해소)")
    void graceExpiryAborts() {
        long userId = saveUser("kakao-sw-4");
        long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-sw-4");
        setSessionInstant(sessionId, "agent_lost_at", Instant.now());

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager).deleteRoomQuietly("room-sw-4");
    }

    @Test
    @DisplayName("stale ACTIVE — 행 있으면 ENDED (webhook 전유실 후 정상 종료의 회수)")
    void staleActiveEndsWithTranscript() {
        long userId = saveUser("kakao-sw-5");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-sw-5");
        setSessionInstant(sessionId, "started_at", Instant.now());
        seedTranscript(sessionId);

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
        verify(roomManager).deleteRoomQuietly("room-sw-5");
    }

    @Test
    @DisplayName("stale ACTIVE — 증거 없으면 AGENT_LOST 경유 없이 즉시 ABORTED")
    void staleActiveAbortsWithoutEvidence() {
        long userId = saveUser("kakao-sw-6");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-sw-6");
        setSessionInstant(sessionId, "started_at", Instant.now());
        when(roomManager.probeRoomPresence("room-sw-6", "candidate-" + sessionId))
                .thenReturn(RoomPresence.of(false, false, null));

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        assertThat(sessionInstant(sessionId, "agent_lost_at")).isNull();
    }

    @Test
    @DisplayName("stale ACTIVE — end_requested_at 있는 세션은 fallback 전담이라 건드리지 않는다")
    void staleActiveRespectsFallbackOwnership() {
        long userId = saveUser("kakao-sw-7");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-sw-7");
        setSessionInstant(sessionId, "started_at", Instant.now());
        setSessionInstant(sessionId, "end_requested_at", Instant.now());

        // fallback 컷오프는 미도래(거대 타임아웃), stale 컷오프는 경과 — stale이 선점하면 안 된다
        new SessionSweeper(sessionRepository, transcriptReader, markerReader, roomManager, transitionExecutor,
                new SessionProperties(Duration.ofDays(999), GRACE, STALE, Duration.ofSeconds(10), WINDOW),
                Clock.fixed(Instant.now().plus(Duration.ofHours(2)), ZoneOffset.UTC))
                .sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("stale PENDING — 행 있으면 ENDED (joined 유실 후 정상 종료·전유실의 회수)")
    void stalePendingEndsWithTranscript() {
        long userId = saveUser("kakao-sw-8");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-sw-8");
        seedTranscript(sessionId);

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("stale PENDING — 표식 있으면 ABORTED (판별 ② — 참가자 대조 없이)")
    void stalePendingAbortsWithMarker() {
        long userId = saveUser("kakao-sw-9");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-sw-9");
        seedMarker(sessionId, "HARD_TIMEOUT");

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("stale PENDING ③ — 룸 미존재·AGENT 없음은 확정 증거로 ABORTED + 룸 삭제")
    void stalePendingAbortsWhenAgentAbsent() {
        long userId = saveUser("kakao-sw-10");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-sw-10");
        when(roomManager.probeAgentPresence("room-sw-10")).thenReturn(AgentPresence.absent());

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager).deleteRoomQuietly("room-sw-10");
    }

    @Test
    @DisplayName("stale PENDING ③ — AGENT 관측 시 ACTIVE 복원(started_at=관측 입장 시각) 후 stale ACTIVE가 hard ceiling")
    void stalePendingRestoresActiveOnAgentPresence() {
        long userId = saveUser("kakao-sw-11");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-sw-11");
        Instant joinedAt = Instant.parse("2026-07-31T08:00:00Z");
        when(roomManager.probeAgentPresence("room-sw-11")).thenReturn(AgentPresence.present(joinedAt));

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "started_at")).isEqualTo(joinedAt);

        // hard ceiling — 복원된 세션은 다음 스위프의 stale ACTIVE 판별이 수렴시킨다 (무한 skip 없음)
        expiredSweeper().sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("stale PENDING ③ — 참가자 조회 실패는 이번 회차만 건너뛴다")
    void stalePendingSkipsOnUnknownPresence() {
        long userId = saveUser("kakao-sw-12");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-sw-12");
        when(roomManager.probeAgentPresence("room-sw-12")).thenReturn(AgentPresence.unknown());

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("PENDING");
        verify(roomManager, never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("stale PENDING — 지속 조회 실패에도 대조 상한(임계 4배) 경과 후 대조 없이 강제 정리된다")
    void stalePendingHardCeilingConvergesDespiteUnknown() {
        long userId = saveUser("kakao-sw-15");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-sw-15");
        when(roomManager.probeAgentPresence("room-sw-15")).thenReturn(AgentPresence.unknown());

        // 상한(45m×4=3h) 이전 — 대조 실패로 건너뛴다
        expiredSweeper().sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("PENDING");
        verify(roomManager).probeAgentPresence("room-sw-15");

        // 상한 경과 — 대조 자체를 시도하지 않고 ABORTED 확정 (수렴이 LiveKit 가용성에 비의존)
        sweeperAt(Instant.now().plus(Duration.ofHours(4))).sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager, times(1)).probeAgentPresence("room-sw-15");
        verify(roomManager).deleteRoomQuietly("room-sw-15");
    }

    @Test
    @DisplayName("stale 임계 미경과 PENDING·ACTIVE는 건드리지 않는다")
    void staleLeavesFreshSessions() {
        long userId = saveUser("kakao-sw-13");
        long pendingId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-sw-13");
        long userId2 = saveUser("kakao-sw-14");
        long activeId = sessionInStatus(userId2, null, SessionStatus.ACTIVE, "room-sw-14");
        setSessionInstant(activeId, "started_at", Instant.now());

        freshSweeper().sweep();

        assertThat(statusOfSession(pendingId)).isEqualTo("PENDING");
        assertThat(statusOfSession(activeId)).isEqualTo("ACTIVE");
    }

    // ─── HBB1-308: INTERRUPTED 유예 · stale ACTIVE 대조 ───

    /** INTERRUPTED 유예 컷오프(창 3m + 마진 45s)는 지났지만 대조 상한(창 4배 = 12m)은 안 지난 시점. */
    private SessionSweeper interruptedGraceSweeper() {
        return sweeperAt(Instant.now().plus(Duration.ofMinutes(5)));
    }

    private String candidateOf(long sessionId) {
        return "candidate-" + sessionId;
    }

    @Test
    @DisplayName("INTERRUPTED 유예 — 행 있으면 ENDED (이탈 중 정상 종료의 room_finished 유실 보정)")
    void interruptedGraceEndsWithTranscript() {
        long userId = saveUser("kakao-sw-20");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-sw-20");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());
        seedTranscript(sessionId);

        interruptedGraceSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
        verify(roomManager).deleteRoomQuietly("room-sw-20");
    }

    @Test
    @DisplayName("INTERRUPTED 유예 — 표식 있으면 ABORTED (RECONNECT_TIMEOUT 등 cause 불분기)")
    void interruptedGraceAbortsWithMarker() {
        long userId = saveUser("kakao-sw-21");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-sw-21");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());
        seedMarker(sessionId, "RECONNECT_TIMEOUT");

        interruptedGraceSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager).deleteRoomQuietly("room-sw-21");
    }

    @Test
    @DisplayName("INTERRUPTED 유예 대조 — candidate+AGENT 관측 시 ACTIVE 복원 (가짜 INTERRUPTED 최종 보정)")
    void interruptedGraceRestoresWhenBothPresent() {
        long userId = saveUser("kakao-sw-22");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-sw-22");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());
        when(roomManager.probeRoomPresence("room-sw-22", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(true, true, null));

        interruptedGraceSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "disconnected_at")).isNull();
        verify(roomManager, never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("INTERRUPTED 유예 대조 — candidate만 관측은 skip (에이전트 소실 판별 경로에 양보)")
    void interruptedGraceSkipsWhenCandidateOnly() {
        long userId = saveUser("kakao-sw-23");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-sw-23");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());
        when(roomManager.probeRoomPresence("room-sw-23", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(false, true, null));

        interruptedGraceSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
        verify(roomManager, never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("INTERRUPTED 유예 대조 — 부재·룸 미존재는 ABORTED + 룸 삭제")
    void interruptedGraceAbortsWhenAbsent() {
        long userId = saveUser("kakao-sw-24");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-sw-24");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());
        when(roomManager.probeRoomPresence("room-sw-24", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(false, false, null));

        interruptedGraceSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        assertThat(sessionInstant(sessionId, "ended_at")).isNotNull();
        verify(roomManager).deleteRoomQuietly("room-sw-24");
    }

    @Test
    @DisplayName("INTERRUPTED 유예 — 대조 실패는 skip하되 상한(창 4배) 경과 후 대조 없이 정리된다")
    void interruptedGraceCeilingConvergesDespiteUnknown() {
        long userId = saveUser("kakao-sw-25");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-sw-25");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());
        when(roomManager.probeRoomPresence("room-sw-25", candidateOf(sessionId)))
                .thenReturn(RoomPresence.unknown());

        interruptedGraceSweeper().sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
        verify(roomManager, times(1)).probeRoomPresence("room-sw-25", candidateOf(sessionId));

        // 상한(3m×4=12m) 경과 — 대조를 시도하지 않고 확정 (수렴의 LiveKit 가용성 비의존)
        sweeperAt(Instant.now().plus(Duration.ofMinutes(20))).sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager, times(1)).probeRoomPresence("room-sw-25", candidateOf(sessionId));
    }

    @Test
    @DisplayName("INTERRUPTED — end_requested_at 있는 세션은 유예 스위퍼가 건드리지 않는다 (fallback 전담)")
    void interruptedRespectsFallbackOwnership() {
        long userId = saveUser("kakao-sw-26");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-sw-26");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());
        setSessionInstant(sessionId, "end_requested_at", Instant.now());

        // fallback 컷오프 미도래·유예 컷오프 경과 — 유예가 /end의 정상 종료 창을 선점하면 안 된다
        new SessionSweeper(sessionRepository, transcriptReader, markerReader, roomManager, transitionExecutor,
                new SessionProperties(Duration.ofDays(999), GRACE, STALE, Duration.ofSeconds(10), WINDOW),
                Clock.fixed(Instant.now().plus(Duration.ofMinutes(5)), ZoneOffset.UTC))
                .sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
    }

    @Test
    @DisplayName("fallback — INTERRUPTED도 대상이다 (/end의 ACTIVE 동일 취급 확장)")
    void fallbackCoversInterrupted() {
        long userId = saveUser("kakao-sw-27");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-sw-27");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());
        setSessionInstant(sessionId, "end_requested_at", Instant.now());

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager).deleteRoomQuietly("room-sw-27");
    }

    @Test
    @DisplayName("AGENT_LOST 유예 — 이탈 관측 세션은 재연결 deadline(+마진) 전에 정리하지 않는다 (deadline 충돌 방지)")
    void agentLostGraceWaitsForReconnectDeadline() {
        long userId = saveUser("kakao-sw-30");
        long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-sw-30");
        setSessionInstant(sessionId, "agent_lost_at", Instant.now());
        setSessionInstant(sessionId, "disconnected_at", Instant.now());

        // 유예(90s… 테스트값 60s)는 지났지만 재연결 deadline(3m + 45s)은 미도래 — 기다린다
        sweeperAt(Instant.now().plus(Duration.ofMinutes(2))).sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");

        // 두 deadline 모두 경과 — 정리
        sweeperAt(Instant.now().plus(Duration.ofMinutes(5))).sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager).deleteRoomQuietly("room-sw-30");
    }

    @Test
    @DisplayName("AGENT_LOST 유예 — 정리 전 행 재판별로 행 있으면 ENDED (webhook 전량 유실 병리 보정)")
    void agentLostGraceEndsWithTranscript() {
        long userId = saveUser("kakao-sw-31");
        long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-sw-31");
        setSessionInstant(sessionId, "agent_lost_at", Instant.now());
        seedTranscript(sessionId);

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
        verify(roomManager).deleteRoomQuietly("room-sw-31");
    }

    @Test
    @DisplayName("stale ACTIVE ③ 대조 — 진행 중 면접(candidate+AGENT) 관측 시 이번 회차 skip")
    void staleActiveSkipsWhenInterviewObserved() {
        long userId = saveUser("kakao-sw-28");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-sw-28");
        setSessionInstant(sessionId, "started_at", Instant.now());
        when(roomManager.probeRoomPresence("room-sw-28", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(true, true, null));

        expiredSweeper().sweep();

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        verify(roomManager, never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("stale ACTIVE ③ — 대조 실패는 skip, 상한(임계 4배) 경과 시 대조 없이 정리 (hang 무한 skip 차단)")
    void staleActiveCeilingConvergesDespiteObservation() {
        long userId = saveUser("kakao-sw-29");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-sw-29");
        setSessionInstant(sessionId, "started_at", Instant.now());
        when(roomManager.probeRoomPresence("room-sw-29", candidateOf(sessionId)))
                .thenReturn(RoomPresence.unknown());

        expiredSweeper().sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        verify(roomManager, times(1)).probeRoomPresence("room-sw-29", candidateOf(sessionId));

        // 상한(45m×4=3h) 경과 — 진행 중 관측(candidate+AGENT)이 있어도 대조 자체를 하지 않고 ABORTED
        when(roomManager.probeRoomPresence("room-sw-29", candidateOf(sessionId)))
                .thenReturn(RoomPresence.of(true, true, null));
        sweeperAt(Instant.now().plus(Duration.ofHours(4))).sweep();
        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager, times(1)).probeRoomPresence("room-sw-29", candidateOf(sessionId));
    }
}
