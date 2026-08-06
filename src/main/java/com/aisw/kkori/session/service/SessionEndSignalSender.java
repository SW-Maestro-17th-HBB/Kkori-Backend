package com.aisw.kkori.session.service;

/**
 * 사용자 종료 신호(SendData) 발신 추상. 실제 호출은 벤더 어댑터
 * ({@code global.livekit.LiveKitEndSignalSender})가 담당한다 — 룸·토큰·디스패치와 동일한 격리 구조.
 *
 * <p>SendData는 응답이 없는(fire-and-forget) 계약이라 {@code room_finished} webhook이 사실상의
 * ack다 — 발신 성공은 "전달"이 아니라 "발신 수리"를 뜻하며, 미처리 수렴은 fallback 스위퍼가 담당한다.
 */
public interface SessionEndSignalSender {

    /**
     * 세션 룸에 종료 신호를 발신한다.
     *
     * @throws com.aisw.kkori.global.exception.BusinessException 발신 실패·타임아웃 시
     *         {@code SESSION_END_SIGNAL_FAILED}(S008) — 종료 의도(end_requested_at)는 이미
     *         커밋되어 있어 재시도 없이도 fallback이 종료를 보장한다
     */
    void send(String roomName, long sessionId);
}
