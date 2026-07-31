package com.aisw.kkori.session.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.session.api.LiveKitWebhookApi;
import com.aisw.kkori.session.service.SessionEventService;
import com.aisw.kkori.session.service.SessionWebhookVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * LiveKit webhook 엔드포인트. 바디는 raw 문자열로 받는다 — 역직렬화·재직렬화를 거치면
 * 서명 검증(바디 SHA-256 대조)이 깨진다. 검증·이벤트 변환은 {@link SessionWebhookVerifier},
 * 전이는 {@link SessionEventService}에 위임해 컨트롤러를 얇게 유지한다.
 */
@RestController
@RequiredArgsConstructor
public class LiveKitWebhookController implements LiveKitWebhookApi {

    private final SessionWebhookVerifier webhookVerifier;
    private final SessionEventService sessionEventService;

    @Override
    @PostMapping("/api/v1/webhook/livekit")
    public ApiResponse<Void> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody String body) {
        sessionEventService.handle(webhookVerifier.verify(body, authorization));
        return ApiResponse.success();
    }
}
