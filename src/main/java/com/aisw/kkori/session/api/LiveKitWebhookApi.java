package com.aisw.kkori.session.api;

import com.aisw.kkori.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * LiveKit webhook 수신 명세 (PRD {@code docs/requirements/session/interview-session-completion.md} 기능 1).
 *
 * <p>LiveKit Cloud 전용 엔드포인트라 Swagger에 노출하지 않는다({@code @Hidden}). 인증은 JWT
 * 필터가 아니라 SDK {@code WebhookReceiver} 서명 검증이 담당하며(검증 실패 401), 전이
 * 성공·no-op은 200, 처리 실패는 5xx로 응답해 LiveKit 재전송을 유도한다(전이가 멱등이라 안전).
 */
@Hidden
public interface LiveKitWebhookApi {

    ApiResponse<Void> receive(String authorization, String body);
}
