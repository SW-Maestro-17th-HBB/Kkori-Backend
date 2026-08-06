package com.aisw.kkori.session.service;

import com.aisw.kkori.session.dto.SessionWebhookSignal;

/**
 * webhook 서명 검증·도메인 신호 변환 추상. 실제 검증은 벤더 어댑터
 * ({@code global.livekit.LiveKitWebhookVerifier})가 담당한다 — 룸·토큰·디스패치와 동일한 격리 구조.
 */
public interface SessionWebhookVerifier {

    /**
     * 서명을 검증하고 이벤트를 도메인 신호로 변환한다.
     *
     * @throws com.aisw.kkori.global.exception.BusinessException 서명 무효·바디 변조·헤더 부재 시
     *         {@code UNAUTHORIZED}(C005) — 전이는 실행되지 않는다
     */
    SessionWebhookSignal verify(String body, String authHeader);
}
