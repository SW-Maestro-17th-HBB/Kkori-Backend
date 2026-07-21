package com.aisw.kkori.global.livekit;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.service.SessionTicket;
import com.aisw.kkori.session.service.SessionTicketIssuer;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LiveKit SDK로 룸 입장 토큰(AccessToken JWT)을 서명하는 벤더 어댑터.
 *
 * <p>토큰은 로컬에서 서명되며(발급 시 LiveKit 왕복 없음), API Secret은 서명에만 쓰고
 * 응답·로그 어디에도 남기지 않는다. 발행(publish) 권한은 마이크로 한정하고
 * 카메라·화면공유·데이터 채널 발행을 차단한다. 구독(subscribe)은 소스별 제한이
 * 불가능하므로 전체 허용한다.
 */
@Component
public class LiveKitTokenIssuer implements SessionTicketIssuer {

    private static final String SOURCE_MICROPHONE = "microphone";

    private final LiveKitProperties properties;

    public LiveKitTokenIssuer(LiveKitProperties properties) {
        this.properties = properties;
    }

    @Override
    public SessionTicket issue(long userId, String roomName) {
        try {
            AccessToken token = new AccessToken(properties.apiKey(), properties.apiSecret());
            token.setIdentity(String.valueOf(userId));
            token.setTtl(properties.tokenTtl().toMillis());
            token.addGrants(
                    new RoomJoin(true),
                    new RoomName(roomName),
                    new CanPublish(true),
                    new CanSubscribe(true),
                    new CanPublishSources(List.of(SOURCE_MICROPHONE)),
                    new CanPublishData(false));
            return new SessionTicket(token.toJwt(), properties.url());
        } catch (RuntimeException e) {
            // 서명 실패 원인만 남기고 예외 메시지에 api-secret이 새지 않도록 원인 예외를 그대로 감싸지 않는다.
            throw new BusinessException(ErrorCode.SESSION_TOKEN_ISSUE_FAILED);
        }
    }
}
