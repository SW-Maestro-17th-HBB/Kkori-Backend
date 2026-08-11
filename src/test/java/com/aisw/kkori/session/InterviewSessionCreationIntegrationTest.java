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
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

        Instant issuedAfter = Instant.now();
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

        // 최초 생성 토큰 TTL 회귀 (HBB1-308 — 1h→10m 단축): exp ≈ 발급 시각 + livekit.token-ttl(테스트 기본 10m)
        Instant exp = Instant.ofEpochSecond(expOf(dataText(result, "livekitToken")));
        assertThat(exp).isBetween(issuedAfter.plus(Duration.ofMinutes(10)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
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
        // 이력서 없는 세션의 metadata는 resumeContext 필드 자체가 생략된다 (계약 픽스처와 동일 형태)
        verify(agentDispatcher).dispatch(session.getLivekitRoom(),
                "{\"sessionId\":\"" + session.getId() + "\",\"interviewType\":\"FIVE_MIN\",\"position\":\"FRONTEND\"}");
    }

    @Test
    @DisplayName("생성 성공 시 세션 룸으로 에이전트가 디스패치되고, metadata는 검증 트랜잭션에서 읽은 structured_data로 조립된다")
    void creationDispatchesAgentWithAssembledMetadata() throws Exception {
        long userId = saveUser("kakao-s-5");
        long resumeId = embeddedResume(userId, """
                {"profile": {"name": "시더", "email": "seeder@example.com"},
                 "skills": [{"category": "언어", "items": ["Java"]}],
                 "projects": [], "experiences": []}
                """);

        ResultActions result = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        // 조립 규칙 자체는 단위 테스트(계약 픽스처) 소관 — 여기서는 실제 저장된 이력서의
        // structured_data가 그 세션의 룸·id와 함께 정확히 배선되는지를 자구로 확인한다
        verify(agentDispatcher).dispatch(dataText(result, "livekitRoom"),
                "{\"sessionId\":\"" + dataLong(result, "id") + "\",\"interviewType\":\"THIRTY_MIN\","
                        + "\"position\":\"BACKEND\",\"resumeContext\":\"[기술 스택]\\n- 언어: Java\"}");
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
    @DisplayName("미존재·soft delete된 이력서는 404 R008 — 선생성 룸은 모두 보상 삭제된다")
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

        assertProvisionedRoomsCompensated(2);
    }

    @Test
    @DisplayName("타인의 이력서는 403 R009 — 선생성 룸은 보상 삭제된다")
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

        assertProvisionedRoomsCompensated(1);
    }

    @Test
    @DisplayName("분석 진행 중 이력서는 409 R010, FAILED 이력서는 409 R011 — 선생성 룸은 모두 보상 삭제된다")
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

        assertProvisionedRoomsCompensated(2);
    }

    /** 거부 경로 공통 검증 — 선생성된 룸 전부가 같은 이름으로 보상 삭제됐는지 확인한다. */
    private void assertProvisionedRoomsCompensated(int expectedProvisioned) {
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager, times(expectedProvisioned)).createRoom(room.capture());
        for (String provisioned : room.getAllValues()) {
            verify(roomManager).deleteRoomQuietly(provisioned);
        }
    }

    @Test
    @DisplayName("형식 오류(400)는 컨트롤러에서 차단되어 룸 선생성조차 일어나지 않는다")
    void validationRejectionSkipsRoomProvisioning() throws Exception {
        long userId = saveUser("kakao-s-24");
        long pendingId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-existing");

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(null, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isBadRequest());

        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(statusOfSession(pendingId)).isEqualTo("PENDING");
        verifyNoInteractions(roomManager);
        verifyNoInteractions(agentDispatcher);
    }

    @Test
    @DisplayName("이력서 검증 거부(409)는 DB 무변화 + 선생성된 룸이 보상 삭제된다")
    void resumeRejectionRollsBackAndCompensatesPreProvisionedRoom() throws Exception {
        long userId = saveUser("kakao-s-25");
        long pendingId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-existing-2");

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(failedResume(userId), "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R011"));

        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(statusOfSession(pendingId)).isEqualTo("PENDING");
        // 룸은 트랜잭션 전에 선생성됐다가 거부(롤백)와 함께 보상 삭제된다 — 기존 룸은 미접촉
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verify(roomManager, never()).deleteRoomQuietly("room-existing-2");
        verifyNoInteractions(agentDispatcher);
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
    @DisplayName("진행 중 세션이 있으면 409 S003 — DB 무변화, 선생성 룸만 보상 삭제되고 진행 중 룸은 미접촉")
    void inProgressSessionBlocksCreation(SessionStatus inProgress) throws Exception {
        long userId = saveUser("kakao-s-32-" + inProgress.name());
        long resumeId = embeddedResume(userId);
        String liveRoom = "room-live-" + inProgress.name();
        long sessionId = sessionInStatus(userId, resumeId, inProgress, liveRoom);

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("S003"));

        assertThat(statusOfSession(sessionId)).isEqualTo(inProgress.name());
        assertThat(sessionRepository.count()).isEqualTo(1);
        // 선생성된 룸은 거부(롤백)와 함께 보상 삭제되고, 진행 중 면접의 룸은 건드리지 않는다
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verify(roomManager, never()).deleteRoomQuietly(liveRoom);
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
    @DisplayName("룸 선생성 실패는 500 S002 — DB 무접촉(레코드·교체 없음), 시도한 룸만 보상 삭제")
    void roomCreateFailureLeavesNoTraceAndCompensates() throws Exception {
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

        // 룸 선생성은 DB 접촉 전이다 — 세션도, 기존 PENDING의 교체도 일어나지 않는다 (PRD 기능 2)
        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(statusOfSession(oldPendingId)).isEqualTo("PENDING");

        // 보상 — 타임아웃은 룸이 실제로 만들어졌을 수 있으므로 시도한 이름으로 삭제한다. 기존 룸은 미접촉
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verify(roomManager, never()).deleteRoomQuietly("room-old-s002");
        verifyNoInteractions(agentDispatcher);
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

    // ─── 녹음 시작 (docs/requirements/session/interview-recording.md 기능 1 — 완료 조건 1·2) ───

    @Test
    @DisplayName("생성 성공 시 디스패치 다음에 세션 룸의 녹음이 시작되고 egress_id가 세션 행에 저장된다")
    void creationStartsRecordingAfterDispatchAndStoresEgressId() throws Exception {
        long userId = saveUser("kakao-s-50");
        when(sessionRecorder.startRecording(anyString())).thenReturn("EG_created");

        ResultActions result = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(null, "FIVE_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        String roomName = dataText(result, "livekitRoom");
        InOrder order = inOrder(agentDispatcher, sessionRecorder);
        order.verify(agentDispatcher).dispatch(eq(roomName), anyString());
        order.verify(sessionRecorder).startRecording(roomName);
        assertThat(sessionRepository.findById(dataLong(result, "id")).orElseThrow().getEgressId())
                .isEqualTo("EG_created");
    }

    @Test
    @DisplayName("녹음 시작 실패 시에도 세션 생성 응답은 201이고 egress_id만 NULL로 남는다")
    void recordingFailureDoesNotFailCreation() throws Exception {
        long userId = saveUser("kakao-s-51");
        when(sessionRecorder.startRecording(anyString()))
                .thenThrow(new IllegalStateException("egress 시작 실패"));

        ResultActions result = mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(null, "FIVE_MIN", "BACKEND")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        InterviewSession session = sessionRepository.findById(dataLong(result, "id")).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.PENDING);
        assertThat(session.getEgressId()).isNull();
    }

    @Test
    @DisplayName("디스패치 실패 시 녹음은 시작조차 되지 않는다 (녹음은 디스패치 성공 다음)")
    void dispatchFailureSkipsRecording() throws Exception {
        long userId = saveUser("kakao-s-52");
        doThrow(new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED))
                .when(agentDispatcher).dispatch(anyString(), anyString());

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(null, "FIVE_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S004"));

        verifyNoInteractions(sessionRecorder);
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

    private long expOf(String jwt) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        return objectMapper.readTree(payload).path("exp").asLong();
    }
}
