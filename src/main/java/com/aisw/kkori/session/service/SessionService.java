package com.aisw.kkori.session.service;

import com.aisw.kkori.session.dto.SessionTokenResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 음성 세션 접속 토큰 발급 흐름.
 *
 * <p>요청마다 새 룸 이름을 만들고(세션마다 독립 룸), 인증 유저를 신원으로 하는 입장권을
 * {@link SessionTicketIssuer}에서 받아 응답으로 조립한다. 벤더(LiveKit) 세부는 발급자
 * 어댑터에 격리되어 이 서비스는 알지 못한다.
 */
@Service
public class SessionService {

    private static final String ROOM_PREFIX = "room-";

    private final SessionTicketIssuer ticketIssuer;

    public SessionService(SessionTicketIssuer ticketIssuer) {
        this.ticketIssuer = ticketIssuer;
    }

    /** 인증 유저에게 새 룸의 입장 토큰을 발급한다. */
    public SessionTokenResponse issueToken(long userId) {
        String roomName = ROOM_PREFIX + UUID.randomUUID();
        SessionTicket ticket = ticketIssuer.issue(userId, roomName);
        return new SessionTokenResponse(ticket.token(), ticket.serverUrl(), roomName);
    }
}
