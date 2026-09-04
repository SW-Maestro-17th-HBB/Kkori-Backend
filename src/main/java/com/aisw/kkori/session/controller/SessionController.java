package com.aisw.kkori.session.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.session.api.SessionApi;
import com.aisw.kkori.session.dto.InterviewSessionCreateRequest;
import com.aisw.kkori.session.dto.InterviewSessionCreateResponse;
import com.aisw.kkori.session.service.SessionEndService;
import com.aisw.kkori.session.service.SessionRejoinService;
import com.aisw.kkori.session.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 면접 세션 API 엔드포인트. 명세·문서화는 {@link SessionApi}에 있고, 로직은
 * {@link SessionService}·{@link SessionEndService}에 위임해 컨트롤러를 얇게 유지한다.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController implements SessionApi {

    private final SessionService sessionService;
    private final SessionEndService sessionEndService;
    private final SessionRejoinService sessionRejoinService;

    /** 면접 세션 생성 — 유형·직무·이력서를 검증하고 세션 레코드와 룸·입장 토큰을 발급한다. */
    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InterviewSessionCreateResponse> create(@AuthenticationPrincipal Long userId,
                                                              @Valid @RequestBody InterviewSessionCreateRequest request) {
        return ApiResponse.success(sessionService.create(userId, request));
    }

    /** 면접 세션 종료 — 상태 무관 수리(202), 실제 종료 확정은 room_finished webhook. */
    @Override
    @PostMapping("/{sessionId}/end")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> end(@AuthenticationPrincipal Long userId,
                                 @PathVariable Long sessionId) {
        sessionEndService.end(userId, sessionId);
        return ApiResponse.success();
    }

    /** 면접 세션 재입장 토큰 발급 — 같은 identity, 재연결 deadline 기준 TTL (HBB1-308). */
    @Override
    @PostMapping("/{sessionId}/rejoin")
    public ApiResponse<InterviewSessionCreateResponse> rejoin(@AuthenticationPrincipal Long userId,
                                                              @PathVariable Long sessionId) {
        return ApiResponse.success(sessionRejoinService.rejoin(userId, sessionId));
    }
}
