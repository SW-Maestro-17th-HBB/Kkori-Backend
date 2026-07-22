package com.aisw.kkori.session.service;

/**
 * 세션 전용 룸의 준비·정리 추상. 실제 호출은 벤더 어댑터({@code global.livekit.LiveKitRoomManager})가
 * 담당한다 — 토큰 발급({@link SessionTicketIssuer})과 동일한 격리 구조.
 */
public interface SessionRoomManager {

    /**
     * 룸을 명시적으로 생성한다.
     *
     * @throws com.aisw.kkori.global.exception.BusinessException 생성 실패·타임아웃 시
     *         {@code SESSION_ROOM_CREATE_FAILED}(S002) — 호출 트랜잭션을 롤백시킨다
     */
    void createRoom(String roomName);

    /**
     * 룸을 best-effort로 삭제한다. <b>어떤 경우에도 예외를 던지지 않는다</b>(실패는 로그만) —
     * 트랜잭션 완료 후 정리·보상 경로에서 호출되므로 실패가 응답·커밋 결과에 영향을 주면 안 된다.
     * 존재하지 않는 룸에 대한 삭제 시도도 무해해야 한다.
     */
    void deleteRoomQuietly(String roomName);
}
