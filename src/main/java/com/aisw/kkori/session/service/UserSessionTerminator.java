package com.aisw.kkori.session.service;

import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.repositoryservice.SessionRepositoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/**
 * 탈퇴 시 유저의 진행 중 세션 일괄 종료 (PRD deletion.md 기능 1) — 세션 도메인이 제공하고
 * 계정 도메인의 탈퇴 트랜잭션이 호출한다.
 *
 * <p>두 단계로 나뉜다. (1) {@link #abortAllForWithdrawal}은 호출자의 user 잠금 트랜잭션 안에서
 * non-terminal 세션을 {@code ABORTED}로 <b>선기록</b>하고 룸 이름을 돌려준다. (2)
 * {@link #deleteRoomsAfterCommit}은 그 룸들의 삭제를 <b>커밋 후</b> 응답 스레드 밖에서 best-effort로
 * 수행한다. 순서가 "선기록 후 삭제"로 고정되는 이유는 세션 종료 PRD의 fallback 계약과 같다 —
 * 선기록 없이 룸을 지우면 그 {@code room_finished}가 정상 종료로 오판된다. 선기록 뒤의 webhook은
 * terminal 공통 가드로 no-op이다.
 *
 * <p>룸 삭제를 응답 스레드에서 떼어내는 이유: 카카오 연결 해제 웹훅 경로는 3초 응답 계약이라
 * LiveKit 왕복(api-timeout 3초)을 기다릴 수 없다. 삭제 실패는 무해하다 — 세션은 이미 terminal이고
 * 잔존 룸은 LiveKit empty timeout·에이전트의 재연결 창 소진 처리로 소멸한다.
 */
@Slf4j
@Component
public class UserSessionTerminator {

    private final SessionRepositoryService sessionRepositoryService;
    private final SessionRoomManager roomManager;
    private final AsyncTaskExecutor taskExecutor;

    public UserSessionTerminator(SessionRepositoryService sessionRepositoryService,
                                 SessionRoomManager roomManager,
                                 @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.sessionRepositoryService = sessionRepositoryService;
        this.roomManager = roomManager;
        this.taskExecutor = taskExecutor;
    }

    /**
     * non-terminal 세션 전부를 {@code ABORTED}로 전이하고 정리할 룸 이름을 반환한다.
     * 반드시 user 행 잠금을 보유한 트랜잭션 안에서 호출한다(전이 경로들과의 직렬화 계약).
     * 룸 이름은 벌크 UPDATE가 영속성 컨텍스트를 비우기 전에 수집한다.
     */
    public List<String> abortAllForWithdrawal(Long userId, Instant now) {
        List<InterviewSession> sessions = sessionRepositoryService.findNonTerminalByUserId(userId);
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<String> rooms = sessions.stream().map(InterviewSession::getLivekitRoom).toList();
        int aborted = sessionRepositoryService.abortAllNonTerminalByUserId(userId, now);
        if (aborted != sessions.size()) {
            // user 잠금 하에서는 도달 불가 — 잠금을 공유하지 않는 전이 경로가 끼어들었다는 진단 신호
            log.warn("탈퇴 세션 종료 대상과 전이 수 불일치 (userId={}, expected={}, aborted={})",
                    userId, sessions.size(), aborted);
        }
        log.info("탈퇴로 진행 중 세션 종료 선기록 (userId={}, aborted={})", userId, aborted);
        return rooms;
    }

    /**
     * 커밋 후 룸 삭제를 예약한다 — 트랜잭션이 롤백되면 삭제하지 않는다(선기록 없는 삭제 금지).
     * 실행은 {@code applicationTaskExecutor}에 넘겨 호출 스레드(API·웹훅 응답)를 막지 않는다.
     */
    public void deleteRoomsAfterCommit(List<String> rooms) {
        if (rooms.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitDeletions(rooms);
                }
            });
        } else {
            // 계약 위반(트랜잭션 밖 호출)이지만 정리는 수행한다 — 선기록이 이미 커밋된 상태로 본다
            log.warn("트랜잭션 밖에서 룸 삭제 예약 — 즉시 제출 (rooms={})", rooms.size());
            submitDeletions(rooms);
        }
    }

    private void submitDeletions(List<String> rooms) {
        for (String room : rooms) {
            try {
                taskExecutor.execute(() -> deleteQuietly(room));
            } catch (RuntimeException e) {
                // 실행기 포화·종료 중 거부 — 잔존 룸은 empty timeout으로 소멸한다
                log.warn("룸 삭제 예약 실패 — empty timeout 자연 소멸 대기 (room={}): {}",
                        room, e.getClass().getSimpleName());
            }
        }
    }

    /** 룸 삭제는 quiet 계약이지만, 구현 결함으로 던져도 실행기 스레드 밖으로 전파되지 않게 격리한다. */
    private void deleteQuietly(String room) {
        try {
            roomManager.deleteRoomQuietly(room);
        } catch (RuntimeException e) {
            log.warn("룸 삭제 중 예외 — quiet 계약 위반 가능성, 무시 (room={})", room, e);
        }
    }
}
