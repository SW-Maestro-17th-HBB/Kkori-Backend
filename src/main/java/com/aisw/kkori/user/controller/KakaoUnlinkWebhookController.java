package com.aisw.kkori.user.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.user.api.KakaoUnlinkWebhookApi;
import com.aisw.kkori.user.service.KakaoUnlinkWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카카오 연결 해제 웹훅 엔드포인트. 카카오는 GET 또는 POST(form)로 전송하므로
 * 단일 핸들러가 두 메서드를 받는다({@code consumes} 미지정 — 지정하면 Content-Type
 * 없는 정상 GET이 415로 이탈한다).
 *
 * <p>헤더·파라미터는 전부 {@code required=false} String으로 바인딩한다 — 누락·비정상
 * 형식이 핸들러 진입 전 바인딩 예외로 이탈하면 "무조건 200" 계약이 깨진다. 검증은
 * {@link KakaoUnlinkWebhookService}가 일괄 수행한다.
 */
@RestController
@RequiredArgsConstructor
public class KakaoUnlinkWebhookController implements KakaoUnlinkWebhookApi {

    private final KakaoUnlinkWebhookService kakaoUnlinkWebhookService;

    @Override
    @RequestMapping(value = "/api/v1/webhook/kakao/unlink", method = {RequestMethod.GET, RequestMethod.POST})
    public ApiResponse<Void> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "app_id", required = false) String appId,
            @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "referrer_type", required = false) String referrerType) {
        kakaoUnlinkWebhookService.receive(authorization, appId, userId, referrerType);
        return ApiResponse.success();
    }
}
