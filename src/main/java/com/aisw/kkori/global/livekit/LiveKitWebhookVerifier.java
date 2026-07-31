package com.aisw.kkori.global.livekit;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.dto.SessionWebhookSignal;
import com.aisw.kkori.session.service.SessionWebhookVerifier;
import io.livekit.server.WebhookReceiver;
import livekit.LivekitModels.ParticipantInfo;
import livekit.LivekitWebhook.WebhookEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LiveKit webhook 서명 검증·이벤트 변환 벤더 어댑터.
 *
 * <p>검증은 SDK 내장 {@link WebhookReceiver}가 수행한다(Authorization 헤더의 JWT를 API
 * Secret으로 검증하고 바디 SHA-256을 대조 — 직접 구현하지 않는다, PRD 기능 1). 검증 실패는
 * 원인을 감싸지 않고 {@code UNAUTHORIZED}로만 던진다 — 서명 토큰·Secret 파생 정보를 예외
 * 메시지·로그에 남기지 않는 기존 방침({@link LiveKitRoomManager})과 동일.
 *
 * <p>이벤트 매핑: {@code participant_joined/left/connection_aborted}는 participant
 * {@code kind}(AGENT)로 판별하고, {@code connection_aborted}는 left와 동일 취급한다 —
 * signaling 성립 후 media 연결 실패는 joined 없이 발생할 수 있고 후속 left가 보장되지
 * 않는다(PRD 매핑 표). 그 외 이벤트는 IGNORE.
 */
@Slf4j
@Component
public class LiveKitWebhookVerifier implements SessionWebhookVerifier {

    private final WebhookReceiver receiver;

    public LiveKitWebhookVerifier(LiveKitProperties properties) {
        this.receiver = new WebhookReceiver(properties.apiKey(), properties.apiSecret());
    }

    @Override
    public SessionWebhookSignal verify(String body, String authHeader) {
        if (body == null || authHeader == null || authHeader.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        WebhookEvent event;
        try {
            event = receiver.receive(body, authHeader);
        } catch (Exception e) {
            // 서명 무효·바디 변조·형식 오류 전부 — 원인 미포장(토큰·Secret 유출 방지)
            log.warn("LiveKit webhook 검증 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return toSignal(event);
    }

    private SessionWebhookSignal toSignal(WebhookEvent event) {
        String name = event.getEvent();
        String room = event.getRoom().getName();
        return switch (name) {
            case "participant_joined" -> agentOnly(SessionWebhookSignal.Type.AGENT_JOINED, event, room, name);
            case "participant_left", "participant_connection_aborted" ->
                    agentOnly(SessionWebhookSignal.Type.AGENT_LEFT, event, room, name);
            case "room_finished" -> new SessionWebhookSignal(SessionWebhookSignal.Type.ROOM_FINISHED, room, name);
            default -> SessionWebhookSignal.ignore(name);
        };
    }

    private SessionWebhookSignal agentOnly(SessionWebhookSignal.Type type, WebhookEvent event,
                                           String room, String name) {
        if (event.getParticipant().getKind() != ParticipantInfo.Kind.AGENT) {
            return SessionWebhookSignal.ignore(name);
        }
        return new SessionWebhookSignal(type, room, name);
    }
}
