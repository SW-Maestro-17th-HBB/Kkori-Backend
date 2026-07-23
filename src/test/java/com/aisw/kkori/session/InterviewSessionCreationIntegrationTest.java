package com.aisw.kkori.session;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/sessions} 면접 세션 생성 통합 테스트
 * (docs/requirements/session/interview-session-creation.md 검증 기준 1:1).
 *
 * <p>룸 어댑터는 모킹, 토큰 발급은 실물(로컬 서명)이다. MockMvc 요청은 실제 트랜잭션을
 * 커밋하므로 afterCompletion 동기화(커밋 후 기존 룸 삭제·롤백 보상)도 응답 시점에 이미
 * 실행되어 있다 — mock verify로 검증한다.
 */
class InterviewSessionCreationIntegrationTest extends InterviewSessionIntegrationTestSupport {

    // ─── 정상 생성 ───

    @Test
    @DisplayName("EMBEDDED 본인 이력서 + THIRTY_MIN + BACKEND는 201로 생성되고 레코드·룸·identity가 계약대로다")
    void thirtyMinCreationSucceeds() throws Exception {
        long userId = saveUser("kakao-s-1");
        long resumeId = embeddedResume(userId);

        ResultActions result = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.livekitToken").isNotEmpty())
                .andExpect(jsonPath("$.data.livekitUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.livekitRoom").isNotEmpty());

        long sessionId = dataLong(result, "id");
        String roomName = dataText(result, "livekitRoom");

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getUserId()).isEqualTo(userId);
        assertThat(session.getResumeId()).isEqualTo(resumeId);
        assertThat(session.getInterviewType().name()).isEqualTo("THIRTY_MIN");
        assertThat(session.getPosition().name()).isEqualTo("BACKEND");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.PENDING);
        assertThat(session.getLivekitRoom()).isEqualTo(roomName);

        verify(roomManager).createRoom(roomName);
        assertThat(subjectOf(dataText(result, "livekitToken"))).isEqualTo("candidate-" + sessionId);
    }

    @Test
    @DisplayName("FIVE_MIN은 resumeId 없이도 201로 생성되고 레코드의 resume_id가 NULL이다")
    void fiveMinWithoutResumeSucceeds() throws Exception {
        long userId = saveUser("kakao-s-2");

        ResultActions result = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(null, "FIVE_MIN", "FRONTEND")))
                .andExpect(status().isCreated());

        InterviewSession session = sessionRepository.findById(dataLong(result, "id")).orElseThrow();
        assertThat(session.getResumeId()).isNull();
        verify(roomManager).createRoom(session.getLivekitRoom());
    }

    @Test
    @DisplayName("FIVE_MIN + 유효한 이력서는 201로 생성되고 resume_id가 기록된다")
    void fiveMinWithValidResumeSucceeds() throws Exception {
        long userId = saveUser("kakao-s-3");
        long resumeId = embeddedResume(userId);

        ResultActions result = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "FIVE_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        assertThat(sessionRepository.findById(dataLong(result, "id")).orElseThrow().getResumeId())
                .isEqualTo(resumeId);
    }

    @Test
    @DisplayName("연속 두 요청은 서로 다른 세션·룸을 반환한다")
    void consecutiveRequestsYieldDistinctSessionsAndRooms() throws Exception {
        long userId = saveUser("kakao-s-4");
        long resumeId = embeddedResume(userId);

        ResultActions first = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());
        ResultActions second = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        assertThat(dataLong(first, "id")).isNotEqualTo(dataLong(second, "id"));
        assertThat(dataText(first, "livekitRoom")).isNotEqualTo(dataText(second, "livekitRoom"));
    }

    // ─── 요청 형식 검증 (400 C002) ───

    @Test
    @DisplayName("interviewType 누락은 400 C002 + fieldErrors")
    void missingInterviewTypeIsRejected() throws Exception {
        long userId = saveUser("kakao-s-10");
        long resumeId = embeddedResume(userId);

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, null, "BACKEND")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("interviewType"));
    }

    @Test
    @DisplayName("미정의 interviewType 값은 400 C002 + fieldErrors(field=interviewType)")
    void undefinedInterviewTypeIsRejected() throws Exception {
        long userId = saveUser("kakao-s-11");
        long resumeId = embeddedResume(userId);

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "TEN_MIN", "BACKEND")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("interviewType"));
    }

    @Test
    @DisplayName("position 누락·미정의 값은 400 C002 + fieldErrors")
    void invalidPositionIsRejected() throws Exception {
        long userId = saveUser("kakao-s-12");
        long resumeId = embeddedResume(userId);

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("position"));

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "FULLSTACK")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("position"));
    }

    @Test
    @DisplayName("THIRTY_MIN에서 resumeId 누락은 400 C002이고 fieldErrors의 field가 정확히 resumeId다")
    void thirtyMinWithoutResumeIsRejected() throws Exception {
        long userId = saveUser("kakao-s-13");

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(null, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("resumeId"));
    }

    // ─── 이력서 검증 (404/403/409) ───

    @Test
    @DisplayName("미존재·soft delete된 이력서는 404 R008 — FIVE_MIN이 무효 resumeId를 낸 경우 포함")
    void missingOrDeletedResumeIsRejected() throws Exception {
        long userId = saveUser("kakao-s-20");

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(999_999L, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("R008"));

        long deletedResumeId = embeddedResume(userId);
        jdbcTemplate.update("UPDATE resumes SET deleted_at = now() WHERE id = ?", deletedResumeId);
        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(deletedResumeId, "FIVE_MIN", "BACKEND")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("R008"));
    }

    @Test
    @DisplayName("타인의 이력서는 403 R009")
    void othersResumeIsRejected() throws Exception {
        long userId = saveUser("kakao-s-21");
        long otherUserId = saveUser("kakao-s-22");
        long othersResumeId = embeddedResume(otherUserId);

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(othersResumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("R009"));
    }

    @Test
    @DisplayName("분석 진행 중 이력서는 409 R010, FAILED 이력서는 409 R011")
    void notEmbeddedResumeIsRejected() throws Exception {
        long userId = saveUser("kakao-s-23");

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(inProgressResume(userId), "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R010"));

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(failedResume(userId), "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R011"));
    }

    @Test
    @DisplayName("거부된 요청은 세션을 만들지도, 기존 세션을 정리하지도, 룸을 만지지도 않는다")
    void rejectedRequestsHaveNoSideEffects() throws Exception {
        long userId = saveUser("kakao-s-24");
        long pendingId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-existing");

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(null, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(failedResume(userId), "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isConflict());

        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(statusOfSession(pendingId)).isEqualTo("PENDING");
        verifyNoInteractions(roomManager);
    }

    // ─── 기존 세션 정리 ───

    @Test
    @DisplayName("기존 PENDING 세션은 ABORTED로 교체되고(ended_at 기록) 그 룸은 커밋 후 삭제된다")
    void existingPendingIsReplacedAndItsRoomDeleted() throws Exception {
        long userId = saveUser("kakao-s-30");
        long resumeId = embeddedResume(userId);
        long oldSessionId = sessionInStatus(userId, resumeId, SessionStatus.PENDING, "room-old");

        ResultActions result = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        InterviewSession old = sessionRepository.findById(oldSessionId).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(SessionStatus.ABORTED);
        assertThat(old.getEndedAt()).isNotNull();
        assertThat(sessionRepository.findById(dataLong(result, "id")).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.PENDING);

        // MockMvc는 실제 트랜잭션을 커밋하므로 afterCompletion(COMMITTED)이 이미 실행됐다
        verify(roomManager).deleteRoomQuietly("room-old");
    }

    @Test
    @DisplayName("기존 룸 삭제가 실패해도 생성 응답은 201로 유지된다 (best-effort)")
    void oldRoomDeleteFailureDoesNotAffectResponse() throws Exception {
        long userId = saveUser("kakao-s-31");
        long resumeId = embeddedResume(userId);
        sessionInStatus(userId, resumeId, SessionStatus.PENDING, "room-old-fail");
        doThrow(new RuntimeException("delete failed")).when(roomManager).deleteRoomQuietly(anyString());

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = {"ACTIVE", "INTERRUPTED", "AGENT_LOST"})
    @DisplayName("진행 중 세션이 있으면 409 S003으로 거부되고 아무것도 변하지 않는다")
    void inProgressSessionBlocksCreation(SessionStatus inProgress) throws Exception {
        long userId = saveUser("kakao-s-32-" + inProgress.name());
        long resumeId = embeddedResume(userId);
        long sessionId = sessionInStatus(userId, resumeId, inProgress, "room-live-" + inProgress.name());

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("S003"));

        assertThat(statusOfSession(sessionId)).isEqualTo(inProgress.name());
        assertThat(sessionRepository.count()).isEqualTo(1);
        verifyNoInteractions(roomManager);
    }

    @Test
    @DisplayName("terminal 세션(ENDED/ABORTED)은 정리 대상이 아니다 (no-op 가드)")
    void terminalSessionsAreUntouched() throws Exception {
        long userId = saveUser("kakao-s-33");
        long resumeId = embeddedResume(userId);
        long endedId = sessionInStatus(userId, resumeId, SessionStatus.ENDED, "room-ended");
        long abortedId = sessionInStatus(userId, resumeId, SessionStatus.ABORTED, "room-aborted");

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        assertThat(statusOfSession(endedId)).isEqualTo("ENDED");
        assertThat(statusOfSession(abortedId)).isEqualTo("ABORTED");
        verify(roomManager, never()).deleteRoomQuietly("room-ended");
        verify(roomManager, never()).deleteRoomQuietly("room-aborted");
    }

    // ─── 룸 생성 실패 (S002) ───

    @Test
    @DisplayName("룸 생성 실패는 500 S002 — 커밋된 PENDING 잔존·기존 세션 ABORTED 확정·신규 룸 보상 삭제 시도")
    void roomCreateFailureLeavesCommittedPendingAndCompensates() throws Exception {
        long userId = saveUser("kakao-s-40");
        long resumeId = embeddedResume(userId);
        long oldPendingId = sessionInStatus(userId, resumeId, SessionStatus.PENDING, "room-old-s002");
        doThrow(new BusinessException(ErrorCode.SESSION_ROOM_CREATE_FAILED))
                .when(roomManager).createRoom(anyString());

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S002"));

        // 커밋은 룸 생성보다 먼저다 — 신규 PENDING이 남고, 기존 세션의 교체(ABORTED)도 확정된다.
        // 잔여물은 다음 생성의 자동 교체가 수렴시킨다 (PRD 기능 2 — 실패 시 처리)
        assertThat(sessionRepository.count()).isEqualTo(2);
        assertThat(statusOfSession(oldPendingId)).isEqualTo("ABORTED");
        assertThat(sessionRepository.findByUserIdAndStatusIn(userId, java.util.Set.of(SessionStatus.PENDING)))
                .hasSize(1);

        // 보상 — 타임아웃은 룸이 실제로 만들어졌을 수 있으므로 생성 시도한 이름으로 삭제를 시도하고,
        // 확정된 교체의 기존 룸도 성공·실패와 무관하게 정리를 시도한다
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verify(roomManager).deleteRoomQuietly("room-old-s002");
    }

    @Test
    @DisplayName("룸 생성 실패 후 재시도는 잔존 PENDING을 자동 교체하고 정상 생성된다 (실패 잔여물의 수렴 경로)")
    void retryAfterRoomFailureConvergesViaReplacement() throws Exception {
        long userId = saveUser("kakao-s-42");
        long resumeId = embeddedResume(userId);
        doThrow(new BusinessException(ErrorCode.SESSION_ROOM_CREATE_FAILED))
                .when(roomManager).createRoom(anyString());
        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError());

        doNothing().when(roomManager).createRoom(anyString());
        ResultActions retry = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        assertThat(sessionRepository.findByUserIdAndStatusIn(userId, SessionStatus.NON_TERMINAL))
                .hasSize(1)
                .first()
                .satisfies(s -> assertThat(s.getId()).isEqualTo(dataLong(retry, "id")));
    }

    // ─── 인증 ───

    @Test
    @DisplayName("AT 없이 또는 무효 AT로 호출하면 401 C005")
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(post(SESSIONS_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1L, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1L, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("탈퇴된 유저의 생성 요청은 401로 거부된다")
    void withdrawnUserIsRejected() throws Exception {
        long userId = saveUser("kakao-s-41");
        String bearer = bearerOf(userId);
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", userId);

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(null, "FIVE_MIN", "BACKEND")))
                .andExpect(status().isUnauthorized());
        assertThat(sessionRepository.count()).isZero();
    }

    // ─── 응답 파싱 헬퍼 ───

    private long dataLong(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path(field).asLong();
    }

    private String dataText(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path(field).asText();
    }

    /** JWT payload의 sub(identity) — 서명 검증은 LiveKitTokenIssuerTest 소관이라 여기선 디코딩만 한다. */
    private String subjectOf(String jwt) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        return objectMapper.readTree(payload).path("sub").asText();
    }
}
