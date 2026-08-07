package com.aisw.kkori.session;

import com.aisw.kkori.LiveKitWebhookTestSigner;
import com.aisw.kkori.global.livekit.LiveKitProperties;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.RoomPresence;
import com.aisw.kkori.session.dto.SessionWebhookSignal;
import com.aisw.kkori.session.service.SessionEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * webhook 상태 전이·판별 3-경로 검증 (PRD interview-session-completion.md 기능 1·3).
 *
 * <p>전이 매트릭스는 검증을 이미 통과한 도메인 신호로 서비스를 직접 호출해 검증하고(서명
 * 검증은 {@code LiveKitWebhookVerifierTest} 소관), 엔드포인트 배선(permitAll·서명 401·전이
 * 관통)은 MockMvc로 별도 확인한다.
 */
class SessionEventServiceIntegrationTest extends SessionCompletionTestSupport {

    @Autowired
    private SessionEventService eventService;

    @Autowired
    private LiveKitProperties liveKitProperties;

    private void handle(SessionWebhookSignal.Type type, String room) {
        eventService.handle(new SessionWebhookSignal(type, room, "test-event"));
    }

    @Nested
    @DisplayName("participant_joined(AGENT)")
    class AgentJoined {

        @Test
        @DisplayName("PENDING 세션이 ACTIVE로 전환되고 started_at이 기록된다")
        void activatesPending() {
            long userId = saveUser("kakao-ev-1");
            long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-ev-1");

            handle(SessionWebhookSignal.Type.AGENT_JOINED, "room-ev-1");

            assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
            assertThat(sessionInstant(sessionId, "started_at")).isNotNull();
        }

        @Test
        @DisplayName("중복 전달은 no-op이다 — 이미 ACTIVE면 started_at이 바뀌지 않는다")
        void duplicateIsNoop() {
            long userId = saveUser("kakao-ev-2");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-2");
            Instant original = Instant.parse("2026-07-31T09:00:00Z");
            setSessionInstant(sessionId, "started_at", original);

            handle(SessionWebhookSignal.Type.AGENT_JOINED, "room-ev-2");

            assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
            assertThat(sessionInstant(sessionId, "started_at")).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("room_finished — terminal 확정 원칙(행 판별)")
    class RoomFinished {

        @Test
        @DisplayName("ACTIVE + 행 있음 → ENDED (ended_at 기록)")
        void activeWithTranscriptEnds() {
            long userId = saveUser("kakao-ev-3");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-3");
            seedTranscript(sessionId);

            handle(SessionWebhookSignal.Type.ROOM_FINISHED, "room-ev-3");

            assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
            assertThat(sessionInstant(sessionId, "ended_at")).isNotNull();
        }

        @Test
        @DisplayName("ACTIVE + 행 없음 → ABORTED (transcript 없는 ENDED 불가)")
        void activeWithoutTranscriptAborts() {
            long userId = saveUser("kakao-ev-4");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-4");

            handle(SessionWebhookSignal.Type.ROOM_FINISHED, "room-ev-4");

            assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        }

        @Test
        @DisplayName("PENDING + 행 있음 → ENDED (joined 유실 병리 — 상태 단정이 아닌 행 판별)")
        void pendingWithTranscriptEnds() {
            long userId = saveUser("kakao-ev-5");
            long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-ev-5");
            seedTranscript(sessionId);

            handle(SessionWebhookSignal.Type.ROOM_FINISHED, "room-ev-5");

            assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
        }

        @Test
        @DisplayName("PENDING + 행 없음 → ABORTED (미입장·디스패치 실패 잔존의 자동 정리)")
        void pendingWithoutTranscriptAborts() {
            long userId = saveUser("kakao-ev-6");
            long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-ev-6");

            handle(SessionWebhookSignal.Type.ROOM_FINISHED, "room-ev-6");

            assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        }

        @Test
        @DisplayName("AGENT_LOST → ABORTED (candidate 이탈 연쇄의 수렴점 — 유예 만료보다 선착)")
        void agentLostAborts() {
            long userId = saveUser("kakao-ev-7");
            long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-ev-7");

            handle(SessionWebhookSignal.Type.ROOM_FINISHED, "room-ev-7");

            assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        }

        @Test
        @DisplayName("terminal 세션은 no-op이다 — fallback 삭제·교체 룸 소멸의 후속 이벤트 흡수")
        void terminalIsNoop() {
            long userId = saveUser("kakao-ev-8");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ENDED, "room-ev-8");
            seedTranscript(sessionId);

            handle(SessionWebhookSignal.Type.ROOM_FINISHED, "room-ev-8");

            assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
            assertThat(sessionInstant(sessionId, "ended_at")).isNull();
        }

        @Test
        @DisplayName("미등록 룸 이벤트는 no-op이다")
        void unknownRoomIsNoop() {
            handle(SessionWebhookSignal.Type.ROOM_FINISHED, "room-unknown");
        }
    }

    @Nested
    @DisplayName("participant_left(AGENT) — 판별 3-경로")
    class AgentLeft {

        @Test
        @DisplayName("① 행 있음 → ENDED + 잔존 룸 정리 (에이전트 룸 삭제 실패 경로)")
        void transcriptEvidenceEnds() {
            long userId = saveUser("kakao-ev-9");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-9");
            seedTranscript(sessionId);

            handle(SessionWebhookSignal.Type.AGENT_LEFT, "room-ev-9");

            assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
            verify(roomManager).deleteRoomQuietly("room-ev-9");
        }

        @Test
        @DisplayName("② 행 없음 + 표식 있음 → ABORTED + 룸 정리 (flush 실패 경로)")
        void markerEvidenceAborts() {
            long userId = saveUser("kakao-ev-10");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-10");
            seedMarker(sessionId, "USER_REQUEST");

            handle(SessionWebhookSignal.Type.AGENT_LEFT, "room-ev-10");

            assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
            verify(roomManager).deleteRoomQuietly("room-ev-10");
        }

        @Test
        @DisplayName("② 파싱 불가 표식도 존재로 취급된다 — cause 불분기 계약")
        void unparseableMarkerStillCounts() {
            long userId = saveUser("kakao-ev-11");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-11");
            seedUnparseableMarker(sessionId);

            handle(SessionWebhookSignal.Type.AGENT_LEFT, "room-ev-11");

            assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        }

        @Test
        @DisplayName("③ 증거 없음 → AGENT_LOST (agent_lost_at 기록, 룸은 남긴다 — 재dispatch 여지)")
        void noEvidenceMarksAgentLost() {
            long userId = saveUser("kakao-ev-12");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-12");

            handle(SessionWebhookSignal.Type.AGENT_LEFT, "room-ev-12");

            assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");
            assertThat(sessionInstant(sessionId, "agent_lost_at")).isNotNull();
            verify(roomManager, never()).deleteRoomQuietly(anyString());
        }

        @Test
        @DisplayName("PENDING에서도 판별이 실행된다 — joined/left 역순 병리 수렴")
        void pendingIsArbitrated() {
            long userId = saveUser("kakao-ev-13");
            long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-ev-13");

            handle(SessionWebhookSignal.Type.AGENT_LEFT, "room-ev-13");

            assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");
        }

        @Test
        @DisplayName("terminal 세션은 no-op이다")
        void terminalIsNoop() {
            long userId = saveUser("kakao-ev-14");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ABORTED, "room-ev-14");

            handle(SessionWebhookSignal.Type.AGENT_LEFT, "room-ev-14");

            assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        }

        @Test
        @DisplayName("INTERRUPTED에서도 판별이 실행되고 ③은 disconnected_at을 보존한다 (교차곱 — HBB1-308)")
        void interruptedIsArbitratedPreservingAnchor() {
            long userId = saveUser("kakao-ev-17");
            long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-ev-17");
            Instant disconnectedAt = Instant.parse("2026-08-01T10:00:00Z");
            setSessionInstant(sessionId, "disconnected_at", disconnectedAt);

            handle(SessionWebhookSignal.Type.AGENT_LEFT, "room-ev-17");

            assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");
            assertThat(sessionInstant(sessionId, "agent_lost_at")).isNotNull();
            assertThat(sessionInstant(sessionId, "disconnected_at")).isEqualTo(disconnectedAt);
        }
    }

    @Nested
    @DisplayName("participant_left(candidate) — INTERRUPTED 전이 (HBB1-308)")
    class CandidateLeft {

        @Test
        @DisplayName("ACTIVE → INTERRUPTED + disconnected_at 기록 (즉시 대조가 candidate 부재를 확인)")
        void activeInterrupts() {
            long userId = saveUser("kakao-ev-20");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-20");
            when(roomManager.probeRoomPresence("room-ev-20", "candidate-" + sessionId))
                    .thenReturn(RoomPresence.of(true, false, null));

            handle(SessionWebhookSignal.Type.CANDIDATE_LEFT, "room-ev-20");

            assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
            assertThat(sessionInstant(sessionId, "disconnected_at")).isNotNull();
        }

        @Test
        @DisplayName("즉시 대조 — candidate+AGENT 실존 시 바로 ACTIVE 복원 (가짜 INTERRUPTED 보정)")
        void immediateProbeRestores() {
            long userId = saveUser("kakao-ev-21");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-21");
            when(roomManager.probeRoomPresence("room-ev-21", "candidate-" + sessionId))
                    .thenReturn(RoomPresence.of(true, true, null));

            handle(SessionWebhookSignal.Type.CANDIDATE_LEFT, "room-ev-21");

            assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
            assertThat(sessionInstant(sessionId, "disconnected_at")).isNull();
        }

        @Test
        @DisplayName("즉시 대조 실패는 무시된다 — INTERRUPTED 유지, 수렴은 스위퍼 몫")
        void immediateProbeFailureKeepsInterrupted() {
            long userId = saveUser("kakao-ev-22");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-22");
            when(roomManager.probeRoomPresence("room-ev-22", "candidate-" + sessionId))
                    .thenReturn(RoomPresence.unknown());

            handle(SessionWebhookSignal.Type.CANDIDATE_LEFT, "room-ev-22");

            assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
        }

        @Test
        @DisplayName("AGENT_LOST 중 이탈은 disconnected_at만 기록한다 — first-wins (재연결 deadline 앵커)")
        void agentLostRecordsDisconnectedAtOnce() {
            long userId = saveUser("kakao-ev-23");
            long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-ev-23");

            handle(SessionWebhookSignal.Type.CANDIDATE_LEFT, "room-ev-23");
            Instant first = sessionInstant(sessionId, "disconnected_at");
            assertThat(first).isNotNull();
            assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");

            handle(SessionWebhookSignal.Type.CANDIDATE_LEFT, "room-ev-23");
            assertThat(sessionInstant(sessionId, "disconnected_at")).isEqualTo(first);
        }

        @Test
        @DisplayName("INTERRUPTED 중 중복 이탈은 no-op — disconnected_at 불변 (창 연장 금지)")
        void interruptedDuplicateKeepsAnchor() {
            long userId = saveUser("kakao-ev-24");
            long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-ev-24");
            Instant anchor = Instant.parse("2026-08-01T09:00:00Z");
            setSessionInstant(sessionId, "disconnected_at", anchor);

            handle(SessionWebhookSignal.Type.CANDIDATE_LEFT, "room-ev-24");

            assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
            assertThat(sessionInstant(sessionId, "disconnected_at")).isEqualTo(anchor);
        }

        @Test
        @DisplayName("PENDING의 candidate 이탈은 no-op — 선입장 이탈은 empty timeout이 수렴")
        void pendingIsNoop() {
            long userId = saveUser("kakao-ev-25");
            long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-ev-25");

            handle(SessionWebhookSignal.Type.CANDIDATE_LEFT, "room-ev-25");

            assertThat(statusOfSession(sessionId)).isEqualTo("PENDING");
            assertThat(sessionInstant(sessionId, "disconnected_at")).isNull();
        }
    }

    @Nested
    @DisplayName("participant_joined(candidate) — 대조 복귀 (HBB1-308)")
    class CandidateJoined {

        @Test
        @DisplayName("INTERRUPTED + candidate·AGENT 관측 → ACTIVE 복귀, disconnected_at 초기화, started_at 불변")
        void resumesWhenBothPresent() {
            long userId = saveUser("kakao-ev-30");
            long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-ev-30");
            Instant startedAt = Instant.parse("2026-08-01T08:00:00Z");
            setSessionInstant(sessionId, "started_at", startedAt);
            setSessionInstant(sessionId, "disconnected_at", Instant.now());
            when(roomManager.probeRoomPresence("room-ev-30", "candidate-" + sessionId))
                    .thenReturn(RoomPresence.of(true, true, null));

            handle(SessionWebhookSignal.Type.CANDIDATE_JOINED, "room-ev-30");

            assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
            assertThat(sessionInstant(sessionId, "disconnected_at")).isNull();
            assertThat(sessionInstant(sessionId, "started_at")).isEqualTo(startedAt);
        }

        @Test
        @DisplayName("candidate만 관측(AGENT 부재)이면 INTERRUPTED 유지 — 에이전트 없는 ACTIVE 금지")
        void keepsInterruptedWhenAgentAbsent() {
            long userId = saveUser("kakao-ev-31");
            long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-ev-31");
            setSessionInstant(sessionId, "disconnected_at", Instant.now());
            when(roomManager.probeRoomPresence("room-ev-31", "candidate-" + sessionId))
                    .thenReturn(RoomPresence.of(false, true, null));

            handle(SessionWebhookSignal.Type.CANDIDATE_JOINED, "room-ev-31");

            assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
            assertThat(sessionInstant(sessionId, "disconnected_at")).isNotNull();
        }

        @Test
        @DisplayName("대조 실패는 500으로 끝나 webhook 재전송을 유도한다 (전이는 멱등)")
        void probeFailureThrows() {
            long userId = saveUser("kakao-ev-32");
            long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-ev-32");
            setSessionInstant(sessionId, "disconnected_at", Instant.now());
            when(roomManager.probeRoomPresence("room-ev-32", "candidate-" + sessionId))
                    .thenReturn(RoomPresence.unknown());

            assertThatThrownBy(() -> handle(SessionWebhookSignal.Type.CANDIDATE_JOINED, "room-ev-32"))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
        }

        @Test
        @DisplayName("AGENT_LOST의 candidate joined는 no-op — 대조 자체를 하지 않는다")
        void agentLostIsNoop() {
            long userId = saveUser("kakao-ev-33");
            long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-ev-33");

            handle(SessionWebhookSignal.Type.CANDIDATE_JOINED, "room-ev-33");

            assertThat(statusOfSession(sessionId)).isEqualTo("AGENT_LOST");
            verify(roomManager, never()).probeRoomPresence(anyString(), anyString());
        }

        @Test
        @DisplayName("ACTIVE·PENDING의 candidate joined는 no-op (구 토큰 재입장 흡수 유지)")
        void otherStatesAreNoop() {
            long userId = saveUser("kakao-ev-34");
            long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-ev-34");

            handle(SessionWebhookSignal.Type.CANDIDATE_JOINED, "room-ev-34");

            assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
            verify(roomManager, never()).probeRoomPresence(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("엔드포인트 배선 (POST /api/v1/webhook/livekit)")
    class Endpoint {

        @Test
        @DisplayName("유효 서명 요청이 인증 없이 수리되어 전이까지 관통한다")
        void signedRequestTransitions() throws Exception {
            long userId = saveUser("kakao-ev-15");
            long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-ev-15");
            String body = "{\"event\":\"participant_joined\",\"room\":{\"name\":\"room-ev-15\"},"
                    + "\"participant\":{\"identity\":\"agent\",\"kind\":\"AGENT\"}}";

            mockMvc.perform(post("/api/v1/webhook/livekit")
                            .contentType("application/webhook+json")
                            .header("Authorization", LiveKitWebhookTestSigner.sign(body, liveKitProperties.apiKey(), liveKitProperties.apiSecret()))
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("서명 무효 요청은 401로 거부되고 전이가 일어나지 않는다")
        void invalidSignatureRejected() throws Exception {
            long userId = saveUser("kakao-ev-16");
            long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-ev-16");
            String body = "{\"event\":\"participant_joined\",\"room\":{\"name\":\"room-ev-16\"},"
                    + "\"participant\":{\"identity\":\"agent\",\"kind\":\"AGENT\"}}";

            mockMvc.perform(post("/api/v1/webhook/livekit")
                            .contentType("application/webhook+json")
                            .header("Authorization",
                                    LiveKitWebhookTestSigner.sign(body, liveKitProperties.apiKey(), "another-secret-that-is-not-ours-32bytes!!"))
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("C005"));

            assertThat(statusOfSession(sessionId)).isEqualTo("PENDING");
        }
    }
}
