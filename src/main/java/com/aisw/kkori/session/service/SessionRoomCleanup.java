package com.aisw.kkori.session.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;

import java.util.List;

/**
 * 세션 생성 트랜잭션 완료 후 룸 정리·보상 동기화 (PRD 기능 2 — 처리 순서와 실패 보상).
 *
 * <p>{@code afterCompletion}만 사용한다 — {@code afterCommit}은 예외가 호출자에게 전파되어
 * 커밋된 생성을 500으로 만들 수 있다. 결과별 분기:
 * <ul>
 *   <li><b>COMMITTED</b> — 교체(ABORTED)된 기존 세션들의 룸을 삭제한다. 트랜잭션 안에서
 *       지우면 롤백 시 세션은 non-terminal로 복귀하는데 룸만 사라지는 불일치가 생기므로,
 *       ABORTED가 확정된 커밋 후에만 지운다.</li>
 *   <li><b>ROLLED_BACK</b> — 신규 룸을 보상 삭제한다. 룸 생성 호출이 실패(타임아웃)했더라도
 *       룸이 실제로 만들어졌을 수 있으므로, 이 동기화는 createRoom 호출 <b>전에</b> 등록된다
 *       (미생성 룸 삭제 시도는 quiet 계약이 무해하게 흡수).</li>
 *   <li><b>UNKNOWN</b> — 아무 룸도 지우지 않는다. 커밋됐을 수 있는 세션의 룸을 파괴하지
 *       않기 위함이며, 잔여 룸은 LiveKit empty timeout이 수용한다(WARN 로그로 운영 표시).</li>
 * </ul>
 *
 * <p>트랜잭션과 커넥션이 이미 끝난 시점이므로 DB에 접근하지 않는다(HTTP 호출·로그만).
 */
@Slf4j
class SessionRoomCleanup implements TransactionSynchronization {

    private final SessionRoomManager roomManager;
    private final String newRoom;
    private final List<String> abortedRooms;

    SessionRoomCleanup(SessionRoomManager roomManager, String newRoom, List<String> abortedRooms) {
        this.roomManager = roomManager;
        this.newRoom = newRoom;
        this.abortedRooms = List.copyOf(abortedRooms);
    }

    @Override
    public void afterCompletion(int status) {
        switch (status) {
            case STATUS_COMMITTED -> abortedRooms.forEach(roomManager::deleteRoomQuietly);
            case STATUS_ROLLED_BACK -> roomManager.deleteRoomQuietly(newRoom);
            default -> log.warn("세션 생성 트랜잭션 결과 불명 — 룸을 정리하지 않음 (newRoom={}, abortedRooms={})",
                    newRoom, abortedRooms);
        }
    }
}
