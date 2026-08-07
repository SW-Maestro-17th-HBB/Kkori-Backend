package com.aisw.kkori.session;

import com.aisw.kkori.session.domain.SessionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재입장 토큰 발급 API 검증 (PRD interview-session-reconnection.md 기능 2).
 *
 * <p>identity 동일 보장·deadline 기준 TTL은 발급된 JWT의 클레임(sub·exp)을 직접 디코드해
 * 검증한다 — 서명은 로컬 연산이라 실물 발급 경로가 그대로 실행된다.
 */
class SessionRejoinApiTest extends SessionCompletionTestSupport {

    /** local 기본 계약값 (application-local.yaml — session.reconnect-window). */
    private static final Duration RECONNECT_WINDOW = Duration.ofMinutes(3);

    private String rejoinUri(long sessionId) {
        return SESSIONS_URI + "/" + sessionId + "/rejoin";
    }

    private JsonNode jwtPayload(String token) throws Exception {
        String payload = token.split("\\.")[1];
        return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("INTERRUPTED 세션 — 같은 identity·같은 룸·deadline 기준 만료의 토큰이 발급된다")
    void interruptedIssuesDeadlineBoundToken() throws Exception {
        long userId = saveUser("kakao-rj-1");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-rj-1");
        Instant disconnectedAt = Instant.now();
        setSessionInstant(sessionId, "disconnected_at", disconnectedAt);

        MvcResult result = mockMvc.perform(post(rejoinUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(sessionId))
                .andExpect(jsonPath("$.data.livekitRoom").value("room-rj-1"))
                .andExpect(jsonPath("$.data.livekitUrl").isNotEmpty())
                .andReturn();

        JsonNode payload = jwtPayload(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("livekitToken").asText());
        assertThat(payload.path("sub").asText()).isEqualTo("candidate-" + sessionId);
        // 만료 = disconnected_at + 재연결 창 (동적 TTL — 처리 시간 오차만 허용)
        long expectedExp = disconnectedAt.plus(RECONNECT_WINDOW).getEpochSecond();
        assertThat(payload.path("exp").asLong()).isBetween(expectedExp - 5, expectedExp + 1);
    }

    @Test
    @DisplayName("AGENT_LOST + 이탈 관측 세션도 발급된다 — 재디스패치 진행 중의 선입장 허용")
    void agentLostWithDisconnectIssues() throws Exception {
        long userId = saveUser("kakao-rj-2");
        long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-rj-2");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());

        mockMvc.perform(post(rejoinUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.livekitToken").isNotEmpty());
    }

    @Test
    @DisplayName("이탈 미관측 AGENT_LOST(candidate 재실)는 409 S009")
    void agentLostWithoutDisconnectRejected() throws Exception {
        long userId = saveUser("kakao-rj-3");
        long sessionId = sessionInStatus(userId, null, SessionStatus.AGENT_LOST, "room-rj-3");

        mockMvc.perform(post(rejoinUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("S009"));
    }

    @ParameterizedTest(name = "{0} 세션은 S009")
    @DisplayName("재입장 대상이 아닌 상태는 409 S009 — 이탈 관측이 있어도 상태 관문이 우선한다")
    @ValueSource(strings = {"PENDING", "ACTIVE", "ENDED", "ABORTED"})
    void notRejoinableStatuses(String status) throws Exception {
        long userId = saveUser("kakao-rj-4-" + status);
        long sessionId = sessionInStatus(userId, null, SessionStatus.valueOf(status), "room-rj-4-" + status);
        setSessionInstant(sessionId, "disconnected_at", Instant.now());

        mockMvc.perform(post(rejoinUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("S009"));
    }

    @Test
    @DisplayName("재연결 창 만료 후에는 409 S009 — 만료 토큰이 새로 발급되지 않는다")
    void expiredWindowRejected() throws Exception {
        long userId = saveUser("kakao-rj-5");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-rj-5");
        setSessionInstant(sessionId, "disconnected_at", Instant.now().minus(Duration.ofMinutes(10)));

        mockMvc.perform(post(rejoinUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("S009"));
    }

    @Test
    @DisplayName("중복 발급은 무해하다 — 무상태 발급, 상태·앵커 불변")
    void duplicateIssuanceIsStateless() throws Exception {
        long userId = saveUser("kakao-rj-6");
        long sessionId = sessionInStatus(userId, null, SessionStatus.INTERRUPTED, "room-rj-6");
        Instant disconnectedAt = Instant.now();
        setSessionInstant(sessionId, "disconnected_at", disconnectedAt);

        mockMvc.perform(post(rejoinUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isOk());
        mockMvc.perform(post(rejoinUri(sessionId)).header("Authorization", bearerOf(userId)))
                .andExpect(status().isOk());

        assertThat(statusOfSession(sessionId)).isEqualTo("INTERRUPTED");
        assertThat(sessionInstant(sessionId, "disconnected_at")).isEqualTo(disconnectedAt);
    }

    @Test
    @DisplayName("미존재 세션은 404(S006), 타 유저 세션은 403(S007), 미인증은 401")
    void rejectsInvalidAccess() throws Exception {
        long ownerId = saveUser("kakao-rj-7");
        long otherId = saveUser("kakao-rj-8");
        long sessionId = sessionInStatus(ownerId, null, SessionStatus.INTERRUPTED, "room-rj-7");
        setSessionInstant(sessionId, "disconnected_at", Instant.now());

        mockMvc.perform(post(rejoinUri(999999)).header("Authorization", bearerOf(ownerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("S006"));

        mockMvc.perform(post(rejoinUri(sessionId)).header("Authorization", bearerOf(otherId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("S007"));

        mockMvc.perform(post(rejoinUri(sessionId)))
                .andExpect(status().isUnauthorized());
    }
}
