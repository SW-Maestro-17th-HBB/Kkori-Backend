package com.aisw.kkori.session.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.session.api.SessionApi;
import com.aisw.kkori.session.dto.SessionTokenResponse;
import com.aisw.kkori.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 음성 세션 API 엔드포인트. 명세·문서화는 {@link SessionApi}에 있고, 발급 로직은
 * {@link SessionService}에 위임해 컨트롤러를 얇게 유지한다.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController implements SessionApi {

    private final SessionService sessionService;

    /** 음성 세션 접속 토큰 발급 — 인증 유저에게 새 룸 입장 토큰을 내려준다. */
    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SessionTokenResponse> issueToken(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(sessionService.issueToken(userId));
    }
}
