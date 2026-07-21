package com.aisw.kkori.session.service;

/**
 * 룸 입장에 필요한 벤더 중립 입장권 — 서명된 접속 토큰과 서버 URL.
 *
 * <p>도메인이 LiveKit 등 특정 벤더의 타입을 알 필요 없도록, {@link SessionTicketIssuer}가
 * 반환하는 최소 계약만 담는다.
 */
public record SessionTicket(String token, String serverUrl) {
}
