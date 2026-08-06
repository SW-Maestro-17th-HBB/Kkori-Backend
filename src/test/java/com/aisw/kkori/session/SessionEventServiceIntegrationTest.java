package com.aisw.kkori.session;

import com.aisw.kkori.LiveKitWebhookTestSigner;
import com.aisw.kkori.global.livekit.LiveKitProperties;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.SessionWebhookSignal;
import com.aisw.kkori.session.service.SessionEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
