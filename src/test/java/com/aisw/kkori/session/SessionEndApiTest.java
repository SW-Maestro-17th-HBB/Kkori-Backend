package com.aisw.kkori.session;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.AgentPresence;
import com.aisw.kkori.session.service.SessionEndSignalSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 명시 종료 API 검증 (PRD interview-session-completion.md 기능 2 — 상태 무관 수리).
 */
class SessionEndApiTest extends SessionCompletionTestSupport {

    @MockitoBean
    SessionEndSignalSender endSignalSender;

    private String endUri(long sessionId) {
        return SESSIONS_URI + "/" + sessionId + "/end";
    }

    @Test
    @DisplayName("ACTIVE 세션 /end는 202 수리 — end_requested_at 기록 후 SendData 발신")
    void activeAccepted() throws Exception {
        long userId = saveUser("kakao-end-1");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-end-1");

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "end_requested_at")).isNotNull();
        verify(endSignalSender).send("room-end-1", sessionId);
    }

    @Test
    @DisplayName("중복 /end는 재발신하되 end_requested_at 최초값을 유지한다 — fallback 창 연장 방지")
    void duplicateKeepsFirstTimestamp() throws Exception {
        long userId = saveUser("kakao-end-2");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-end-2");

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted());
        Instant first = sessionInstant(sessionId, "end_requested_at");

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted());

        assertThat(sessionInstant(sessionId, "end_requested_at")).isEqualTo(first);
        verify(endSignalSender, times(2)).send("room-end-2", sessionId);
    }

    @Test
    @DisplayName("PENDING /end — 부재 확정(대조 ABSENT) 시 ABORTED + 룸 삭제, SendData 미호출")
    void pendingAbortedWhenAgentAbsent() throws Exception {
        long userId = saveUser("kakao-end-3");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-end-3");
        when(roomManager.probeAgentPresence("room-end-3")).thenReturn(AgentPresence.absent());

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted());

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        assertThat(sessionInstant(sessionId, "ended_at")).isNotNull();
        verify(roomManager).deleteRoomQuietly("room-end-3");
        verify(endSignalSender, never()).send(anyString(), anyLong());
    }

    @Test
    @DisplayName("PENDING /end — 행 있으면 대조 없이 ENDED (joined·room_finished 전유실 후 정상 종료)")
    void pendingEndsWithTranscript() throws Exception {
        long userId = saveUser("kakao-end-9");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-end-9");
        seedTranscript(sessionId);

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted());

        assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
        verify(roomManager, never()).probeAgentPresence(anyString());
        verify(endSignalSender, never()).send(anyString(), anyLong());
    }

    @Test
    @DisplayName("PENDING /end — AGENT 관측 시 ACTIVE 복원 후 정상 종료 유도 (joined 유실 병리 보호)")
    void pendingRestoredWhenAgentPresent() throws Exception {
        long userId = saveUser("kakao-end-10");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-end-10");
        Instant joinedAt = Instant.parse("2026-07-31T08:00:00Z");
        when(roomManager.probeAgentPresence("room-end-10")).thenReturn(AgentPresence.present(joinedAt));

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted());

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "started_at")).isEqualTo(joinedAt);
        assertThat(sessionInstant(sessionId, "end_requested_at")).isNotNull();
        verify(endSignalSender).send("room-end-10", sessionId);
        verify(roomManager, never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("PENDING /end — 대조 실패(UNKNOWN)는 종료를 단정하지 않고 500(S008), 상태 무변화")
    void pendingProbeFailureIsRetryable() throws Exception {
        long userId = saveUser("kakao-end-11");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-end-11");
        when(roomManager.probeAgentPresence("room-end-11")).thenReturn(AgentPresence.unknown());

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S008"));

        assertThat(statusOfSession(sessionId)).isEqualTo("PENDING");
        verify(roomManager, never()).deleteRoomQuietly(anyString());
        verify(endSignalSender, never()).send(anyString(), anyLong());
    }

    @Test
    @DisplayName("PENDING /end — 대조 중 ACTIVE 전이(webhook 선착) 시 대조 결과를 버리고 SendData로 수렴")
    void pendingProbeRaceFallsToActiveBranch() throws Exception {
        long userId = saveUser("kakao-end-12");
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-end-12");
        // 대조 시점에 전이를 끼워 넣는 결정적 재현 — 대조가 ABSENT를 반환해도 재진입이 최신 상태로 재분기해야 한다
        when(roomManager.probeAgentPresence("room-end-12")).thenAnswer(invocation -> {
            jdbcTemplate.update("UPDATE interview_session SET status = 'ACTIVE', started_at = now() WHERE id = ?",
                    sessionId);
            return AgentPresence.absent();
        });

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted());

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "end_requested_at")).isNotNull();
        verify(endSignalSender).send("room-end-12", sessionId);
        verify(roomManager, never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("AGENT_LOST 세션 /end도 즉시 ABORTED + 룸 삭제")
    void agentLostAbortedImmediately() throws Exception {
        long userId = saveUser("kakao-end-4");
        long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-end-4");

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted());

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        verify(roomManager).deleteRoomQuietly("room-end-4");
    }

    @Test
    @DisplayName("terminal 세션 /end는 멱등 no-op이되 룸 삭제를 재시도한다 — 잔존 룸 기회적 복구")
    void terminalIdempotentWithRoomRetry() throws Exception {
        long userId = saveUser("kakao-end-5");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ENDED, "room-end-5");

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isAccepted());

        assertThat(statusOfSession(sessionId)).isEqualTo("ENDED");
        assertThat(sessionInstant(sessionId, "ended_at")).isNull();
        verify(roomManager).deleteRoomQuietly("room-end-5");
        verify(endSignalSender, never()).send(anyString(), anyLong());
    }

    @Test
    @DisplayName("미존재 세션은 404(S006), 타 유저 세션은 403(S007), 미인증은 401")
    void rejectsInvalidAccess() throws Exception {
        long ownerId = saveUser("kakao-end-6");
        long otherId = saveUser("kakao-end-7");
        long sessionId = sessionInStatus(ownerId, null, SessionStatus.ACTIVE, "room-end-6");

        mockMvc.perform(post(endUri(999999)).header("Authorization", bearerOf(ownerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("S006"));

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(otherId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("S007"));

        mockMvc.perform(post(endUri(sessionId)))
                .andExpect(status().isUnauthorized());

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "end_requested_at")).isNull();
    }

    @Test
    @DisplayName("SendData 실패는 500(S008)이되 end_requested_at은 유지된다 — fallback 수렴 보장")
    void signalFailureKeepsEndIntent() throws Exception {
        long userId = saveUser("kakao-end-8");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-end-8");
        doThrow(new BusinessException(ErrorCode.SESSION_END_SIGNAL_FAILED))
                .when(endSignalSender).send("room-end-8", sessionId);

        mockMvc.perform(post(endUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S008"));

        assertThat(statusOfSession(sessionId)).isEqualTo("ACTIVE");
        assertThat(sessionInstant(sessionId, "end_requested_at")).isNotNull();
    }
}
