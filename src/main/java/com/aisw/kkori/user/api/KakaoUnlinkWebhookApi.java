package com.aisw.kkori.user.api;

import com.aisw.kkori.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * 카카오 연결 해제 웹훅 수신 명세 (PRD {@code docs/requirements/user/account.md} 기능 5).
 *
 * <p>카카오 서버 전용 엔드포인트라 Swagger에 노출하지 않는다({@code @Hidden}).
 * 검증·처리 실패를 포함해 항상 200을 반환한다 — 카카오는 200 외 응답을 실패로
 * 간주하며 연속 실패 시 웹훅을 비활성화한다(카카오 웹훅 명세).
 */
@Hidden
public interface KakaoUnlinkWebhookApi {

    ApiResponse<Void> receive(String authorization, String appId, String userId, String referrerType);
}
