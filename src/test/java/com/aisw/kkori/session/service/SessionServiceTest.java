package com.aisw.kkori.session.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.service.ResumeAccessGuard;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.InterviewSessionCreateRequest;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SessionService}의 순수 단위 검증 — 통합 테스트로 재현할 수 없는 경합 신호를 다룬다.
 */
class SessionServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final InterviewSessionRepository sessionRepository = mock(InterviewSessionRepository.class);
    private final ResumeAccessGuard resumeAccessGuard = mock(ResumeAccessGuard.class);
    private final SessionRoomManager roomManager = mock(SessionRoomManager.class);
    private final SessionTicketIssuer ticketIssuer = mock(SessionTicketIssuer.class);
    private final DispatchMetadataAssembler metadataAssembler = mock(DispatchMetadataAssembler.class);
    private final SessionAgentDispatcher agentDispatcher = mock(SessionAgentDispatcher.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T09:00:00Z"), ZoneOffset.UTC);

    private final SessionService sessionService = new SessionService(
            userRepository, sessionRepository, resumeAccessGuard, roomManager, ticketIssuer,
            metadataAssembler, agentDispatcher, sessionRecorder, transactionTemplate, clock);

    @BeforeEach
    void passThroughTransactionTemplate() {
        // 단위 테스트에선 트랜잭션 의미 없이 콜백만 그대로 실행한다 — 콜백 예외는 호출자에게 전파
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)));
    }

    @Test
    @DisplayName("PENDING 교체 전이 수가 조회 수와 불일치하면 생성을 S003으로 중단한다 (잠금 미공유 전이 경로 방어선)")
    void abortCountMismatchRejectsCreation() {
        // 조회 시점엔 PENDING이었지만 조건부 UPDATE가 0건 — 조회~전이 사이 다른 경로가 상태를 바꾼 상황.
        // user 잠금 하에서는 불가능하므로, 잠금을 공유하지 않는 전이 경로가 생겼다는 신호로 보고 중단해야 한다.
        when(userRepository.findActiveWithLock(anyLong()))
                .thenReturn(User.create("kakao-unit-1", null, null));
        InterviewSession stale = InterviewSession.pending(1L, null, InterviewType.FIVE_MIN, Position.BACKEND, "room-stale");
        when(sessionRepository.findByUserIdAndStatusIn(anyLong(), anyCollection())).thenReturn(List.of(stale));
        when(sessionRepository.abortPendingByIds(anyCollection(), any())).thenReturn(0);

        assertThatThrownBy(() -> sessionService.create(1L,
                new InterviewSessionCreateRequest(null, InterviewType.FIVE_MIN, Position.BACKEND)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_ALREADY_IN_PROGRESS));

        verify(sessionRepository, never()).save(any());
        // 룸은 트랜잭션 전에 선생성됐다가 롤백과 함께 보상 삭제되어야 한다
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verifyNoInteractions(ticketIssuer, agentDispatcher);
    }

    @Test
    @DisplayName("진행 중 상태(IN_PROGRESS) 세션이 있으면 교체 시도 없이 S003으로 거부한다")
    void inProgressSessionRejectsBeforeAbort() {
        when(userRepository.findActiveWithLock(anyLong()))
                .thenReturn(User.create("kakao-unit-2", null, null));
        InterviewSession active = InterviewSession.pending(1L, null, InterviewType.FIVE_MIN, Position.BACKEND, "room-live");
        setStatus(active, SessionStatus.ACTIVE);
        when(sessionRepository.findByUserIdAndStatusIn(anyLong(), anyCollection())).thenReturn(List.of(active));

        assertThatThrownBy(() -> sessionService.create(1L,
                new InterviewSessionCreateRequest(null, InterviewType.FIVE_MIN, Position.BACKEND)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_ALREADY_IN_PROGRESS));

        verify(sessionRepository, never()).abortPendingByIds(anyCollection(), any());
        ArgumentCaptor<String> room = ArgumentCaptor.forClass(String.class);
        verify(roomManager).createRoom(room.capture());
        verify(roomManager).deleteRoomQuietly(room.getValue());
        verifyNoInteractions(ticketIssuer, agentDispatcher);
    }

    /** 상태 전이 메서드는 후속 스토리 소관이라 엔티티에 없다 — 테스트는 리플렉션으로 상태를 주입한다. */
    private static void setStatus(InterviewSession session, SessionStatus status) {
        try {
            var field = InterviewSession.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(session, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
