package com.aisw.kkori.session;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.service.SessionTicketIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토큰 발급 실패 경로의 잔존·보상 검증 (PRD 기능 2 — 실패 시 처리).
 *
 * <p>커밋이 룸 생성·토큰 발급보다 먼저이므로, 이 실패들은 롤백이 아니라 "커밋된 PENDING
 * 잔존 + 보상 삭제"로 처리된다. 토큰 발급자까지 모킹하므로 베이스와 {@code @MockitoBean}
 * 구성이 달라 별도 ApplicationContext를 쓴다 — 그 비용 때문에 이 클래스는 토큰 실패 계열만 담는다.
 */
class InterviewSessionCreationFailureTest extends InterviewSessionIntegrationTestSupport {

    @MockitoBean
    SessionTicketIssuer ticketIssuer;

    @Test
    @DisplayName("토큰 발급 실패는 500 S001 — 커밋된 PENDING 잔존·기존 세션 ABORTED 확정, 신규 룸은 보상 삭제된다")
    void tokenFailureLeavesCommittedPendingAndCompensatesNewRoom() throws Exception {
        long userId = saveUser("kakao-f-1");
        long resumeId = embeddedResume(userId);
        long oldPendingId = sessionInStatus(userId, resumeId, SessionStatus.PENDING, "room-old-s001");
        when(ticketIssuer.issue(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.SESSION_TOKEN_ISSUE_FAILED));

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S001"));

        // 커밋은 토큰 발급보다 먼저다 — 신규 PENDING 잔존 + 기존 세션 ABORTED 확정 (재시도가 교체로 수렴)
        assertThat(sessionRepository.count()).isEqualTo(2);
        assertThat(statusOfSession(oldPendingId)).isEqualTo("ABORTED");

        // 룸은 이미 생성된 뒤 토큰이 실패했다 — 같은 이름으로 보상 삭제, 확정 교체된 기존 룸도 정리 시도
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verify(roomManager).deleteRoomQuietly("room-old-s001");
    }

    @Test
    @DisplayName("보상 삭제가 실패해도 원래의 에러 응답(S001)이 유지된다")
    void compensationFailureKeepsOriginalError() throws Exception {
        long userId = saveUser("kakao-f-2");
        long resumeId = embeddedResume(userId);
        when(ticketIssuer.issue(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.SESSION_TOKEN_ISSUE_FAILED));
        doThrow(new RuntimeException("compensation failed")).when(roomManager).deleteRoomQuietly(anyString());

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S001"));

        // 보상 경로가 실제로 실행됐음을 보장 — 호출 자체가 없으면 doThrow가 발화하지 않아 공허하게 통과한다
        verify(roomManager).deleteRoomQuietly(anyString());
        // 커밋된 PENDING은 보상 실패와 무관하게 남는다
        assertThat(sessionRepository.count()).isEqualTo(1);
    }
}
