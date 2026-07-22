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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토큰 발급 실패 경로의 롤백·보상 검증 (PRD 기능 2 — 실패 시 보상).
 *
 * <p>토큰 발급자까지 모킹하므로 베이스와 {@code @MockitoBean} 구성이 달라 별도
 * ApplicationContext를 쓴다 — 그 비용 때문에 이 클래스는 토큰 실패 계열만 담는다.
 */
class InterviewSessionCreationFailureTest extends InterviewSessionIntegrationTestSupport {

    @MockitoBean
    SessionTicketIssuer ticketIssuer;

    @Test
    @DisplayName("토큰 발급 실패는 500 S001 — 레코드 롤백·기존 PENDING 보존, 이미 만든 신규 룸은 보상 삭제된다")
    void tokenFailureRollsBackAndCompensatesNewRoom() throws Exception {
        long userId = saveUser("kakao-f-1");
        long resumeId = embeddedResume(userId);
        long pendingId = sessionInStatus(userId, resumeId, SessionStatus.PENDING, "room-keep");
        when(ticketIssuer.issue(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.SESSION_TOKEN_ISSUE_FAILED));

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S001"));

        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(statusOfSession(pendingId)).isEqualTo("PENDING");

        // 룸은 이미 생성된 뒤 토큰이 실패했다 — 같은 이름으로 보상 삭제가 시도되어야 한다
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verify(roomManager, never()).deleteRoomQuietly("room-keep");
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
        assertThat(sessionRepository.count()).isZero();
    }
}
