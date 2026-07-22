package com.aisw.kkori.session.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link SessionRoomCleanup}의 트랜잭션 결과별 정리 정책 검증 (PRD 기능 2 — 처리 순서와 실패 보상).
 *
 * <p>특히 UNKNOWN 분기("아무 룸도 삭제하지 않음")는 커밋됐을 수 있는 세션의 룸 파괴를 막는
 * 정책이라, 통합 테스트로는 재현할 수 없어 여기서 직접 고정한다.
 */
class SessionRoomCleanupTest {

    private final SessionRoomManager roomManager = mock(SessionRoomManager.class);
    private final SessionRoomCleanup cleanup =
            new SessionRoomCleanup(roomManager, "room-new", List.of("room-old-1", "room-old-2"));

    @Test
    @DisplayName("COMMITTED — 교체된 기존 룸만 삭제하고 신규 룸은 남긴다")
    void committedDeletesAbortedRoomsOnly() {
        cleanup.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(roomManager).deleteRoomQuietly("room-old-1");
        verify(roomManager).deleteRoomQuietly("room-old-2");
        verify(roomManager, never()).deleteRoomQuietly("room-new");
    }

    @Test
    @DisplayName("ROLLED_BACK — 신규 룸만 보상 삭제하고 기존 룸은 건드리지 않는다")
    void rolledBackCompensatesNewRoomOnly() {
        cleanup.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(roomManager).deleteRoomQuietly("room-new");
        verify(roomManager, never()).deleteRoomQuietly("room-old-1");
        verify(roomManager, never()).deleteRoomQuietly("room-old-2");
    }

    @Test
    @DisplayName("UNKNOWN — 커밋됐을 수 있으므로 어떤 룸도 삭제하지 않는다")
    void unknownDeletesNothing() {
        cleanup.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        verifyNoInteractions(roomManager);
    }
}
