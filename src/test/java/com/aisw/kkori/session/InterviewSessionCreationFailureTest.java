package com.aisw.kkori.session;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.service.SessionTicket;
import com.aisw.kkori.session.service.SessionTicketIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 커밋 후 실패(토큰 발급 S001·디스패치 S004) 경로의 잔존·보상 검증
 * (interview-session-creation.md 기능 2 + agent-dispatch.md 기능 2 — 실패 시 처리).
 *
 * <p>커밋이 토큰 발급·디스패치보다 먼저이므로, 이 실패들은 롤백이 아니라 "커밋된 PENDING
 * 잔존 + 보상 삭제"로 처리된다. 토큰 발급자까지 모킹하므로 베이스와 {@code @MockitoBean}
 * 구성이 달라 별도 ApplicationContext를 쓴다 — 그 비용 때문에 이 클래스는 커밋 후 실패
 * 계열만 담는다(디스패치 성공 경로의 순서 계약도 토큰 발급자 verify가 필요해 여기 포함).
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

        // 순서 계약 — 토큰 발급이 실패하면 디스패치는 호출되지 않는다 (agent-dispatch.md 기능 2)
        verifyNoInteractions(agentDispatcher);
    }

    @Test
    @DisplayName("디스패치는 토큰 발급 성공 후 세션 룸으로 1회 호출된다 (createRoom → issue → dispatch 순서 계약)")
    void dispatchFollowsTokenIssuanceInOrder() throws Exception {
        long userId = saveUser("kakao-f-3");
        long resumeId = embeddedResume(userId);
        when(ticketIssuer.issue(anyString(), anyString())).thenReturn(new SessionTicket("token", "ws://test"));

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        String roomName = sessionRepository.findAll().get(0).getLivekitRoom();
        InOrder inOrder = inOrder(roomManager, ticketIssuer, agentDispatcher);
        inOrder.verify(roomManager).createRoom(roomName);
        inOrder.verify(ticketIssuer).issue("candidate-" + sessionRepository.findAll().get(0).getId(), roomName);
        inOrder.verify(agentDispatcher).dispatch(eq(roomName), anyString());
    }

    @Test
    @DisplayName("디스패치 실패는 500 S004 — 커밋된 PENDING 잔존·기존 세션 ABORTED 확정, 신규 룸 보상 삭제·기존 룸 정리 모두 시도")
    void dispatchFailureLeavesCommittedPendingAndCompensates() throws Exception {
        long userId = saveUser("kakao-f-4");
        long resumeId = embeddedResume(userId);
        long oldPendingId = sessionInStatus(userId, resumeId, SessionStatus.PENDING, "room-old-s004");
        when(ticketIssuer.issue(anyString(), anyString())).thenReturn(new SessionTicket("token", "ws://test"));
        doThrow(new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED))
                .when(agentDispatcher).dispatch(anyString(), anyString());

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S004"));

        // 커밋은 디스패치보다 먼저다 — S001과 동일 모델 (신규 PENDING 잔존 + 교체 확정)
        assertThat(sessionRepository.count()).isEqualTo(2);
        assertThat(statusOfSession(oldPendingId)).isEqualTo("ABORTED");

        // 신규 룸 보상 삭제와 확정 교체된 기존 룸 정리가 모두 시도된다
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verify(roomManager).deleteRoomQuietly("room-old-s004");
    }

    @Test
    @DisplayName("디스패치 실패로 잔존한 PENDING은 같은 유저의 재시도에서 자동 교체되어 정상 생성으로 수렴한다")
    void retryAfterDispatchFailureConvergesViaReplacement() throws Exception {
        long userId = saveUser("kakao-f-5");
        long resumeId = embeddedResume(userId);
        when(ticketIssuer.issue(anyString(), anyString())).thenReturn(new SessionTicket("token", "ws://test"));
        doThrow(new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED))
                .when(agentDispatcher).dispatch(anyString(), anyString());
        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError());

        doNothing().when(agentDispatcher).dispatch(anyString(), anyString());
        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isCreated());

        assertThat(sessionRepository.findByUserIdAndStatusIn(userId, SessionStatus.NON_TERMINAL)).hasSize(1);
    }

    @Test
    @DisplayName("커밋과 응답 사이에 동시 생성이 세션을 교체하면 409 S005 — 자신의 룸을 보상 삭제하고 교체 세션은 유효하다")
    void supersededDuringDispatchIsRejectedAndCompensated() throws Exception {
        long userId = saveUser("kakao-f-7");
        long resumeId = embeddedResume(userId);
        when(ticketIssuer.issue(anyString(), anyString())).thenReturn(new SessionTicket("token", "ws://test"));

        // 첫 디스패치 시점에 두 번째 생성 요청을 통째로 끼워 넣는다 — "A 커밋 → B가 A를
        // 교체(ABORTED)·룸 삭제까지 완료 → A의 늦은 디스패치가 삭제된 룸을 자동 재생성"
        // 인터리빙의 결정적 재현. A의 승계 재확인이 이를 감지해야 한다 (agent-dispatch.md 기능 2)
        AtomicBoolean interleaved = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (interleaved.compareAndSet(false, true)) {
                mockMvc.perform(post(SESSIONS_URI)
                                .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                        .andExpect(status().isCreated());
            }
            return null;
        }).when(agentDispatcher).dispatch(anyString(), anyString());

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("S005"));

        // "마지막 생성이 유효" — A(외부 요청)는 ABORTED, B(끼워 넣은 요청)의 PENDING만 남는다
        List<InterviewSession> nonTerminal =
                sessionRepository.findByUserIdAndStatusIn(userId, SessionStatus.NON_TERMINAL);
        assertThat(nonTerminal).hasSize(1);
        assertThat(sessionRepository.count()).isEqualTo(2);
        InterviewSession abortedA = sessionRepository.findAll().stream()
                .filter(s -> s.getStatus() == SessionStatus.ABORTED).findFirst().orElseThrow();

        // A의 룸은 교체측(B)의 정리와 A 자신의 보상 삭제로 두 번 삭제 시도된다(중복 삭제 무해) —
        // A의 보상이 디스패치가 자동 재생성한 좀비 룸(에이전트 포함)을 소멸시키는 경로다
        verify(roomManager, times(2)).deleteRoomQuietly(abortedA.getLivekitRoom());
        // 유효 세션(B)의 룸은 건드리지 않고, 디스패치는 두 요청 각각 1회씩이다
        verify(roomManager, never()).deleteRoomQuietly(nonTerminal.get(0).getLivekitRoom());
        verify(agentDispatcher, times(2)).dispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("디스패치 실패의 보상 삭제가 실패해도 원래의 에러 응답(S004)이 유지된다")
    void dispatchCompensationFailureKeepsOriginalError() throws Exception {
        long userId = saveUser("kakao-f-6");
        long resumeId = embeddedResume(userId);
        when(ticketIssuer.issue(anyString(), anyString())).thenReturn(new SessionTicket("token", "ws://test"));
        doThrow(new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED))
                .when(agentDispatcher).dispatch(anyString(), anyString());
        doThrow(new RuntimeException("compensation failed")).when(roomManager).deleteRoomQuietly(anyString());

        mockMvc.perform(post(SESSIONS_URI)
                        .header(HttpHeaders.AUTHORIZATION, bearerOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(resumeId, "THIRTY_MIN", "BACKEND")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("S004"));

        verify(roomManager).deleteRoomQuietly(anyString());
        assertThat(sessionRepository.count()).isEqualTo(1);
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
