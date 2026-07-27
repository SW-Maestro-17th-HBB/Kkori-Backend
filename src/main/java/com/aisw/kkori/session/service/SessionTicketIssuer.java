package com.aisw.kkori.session.service;

/**
 * 특정 신원을 특정 룸에 입장시킬 입장권(토큰 + 서버 URL)을 발급하는 추상.
 *
 * <p>도메인({@link SessionService})은 이 인터페이스에만 의존하고, 실제 서명은
 * 벤더 어댑터({@code global.livekit.LiveKitTokenIssuer})가 담당한다 —
 * 벤더 교체 시 도메인 코드를 건드리지 않기 위함이다.
 */
public interface SessionTicketIssuer {

    /**
     * 룸 입장 토큰을 발급한다.
     *
     * @param identity 참가자 신원 — 세션 파생 규칙({@code candidate-{sessionId}})은 도메인이 확정한다
     * @param roomName 입장 대상 룸 이름
     * @return 서명된 접속 토큰과 서버 URL
     */
    SessionTicket issue(String identity, String roomName);
}
