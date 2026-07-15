package com.aisw.kkori.user.service;

import com.aisw.kkori.global.oauth.KakaoOAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 카카오 연결 해제 웹훅 오케스트레이터 (PRD {@code docs/requirements/user/account.md} 기능 5).
 *
 * <p>의도적으로 비트랜잭션이다 — 탈퇴 동기화의 예외는 트랜잭션 프록시 바깥인 여기서
 * 잡아야 커밋·timeout 시점 예외까지 흡수해 "무조건 200" 계약을 지킨다. 단 {@code Error}는
 * 잡지 않는다(OutOfMemoryError 같은 JVM 치명 오류까지 200으로 숨기면 안 됨).
 *
 * <p>연결 해제 웹훅은 카카오 재전송이 없어 처리 실패는 ERROR 로그 기반 수동 조치로만
 * 복구된다(PRD 운영 절차). {@code Authorization} 헤더·어드민 키 원문은 절대 로그 금지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoUnlinkWebhookService {

    private static final String AUTHORIZATION_PREFIX = "KakaoAK ";
    private static final int MAX_LOG_LENGTH = 100;

    private final KakaoOAuthProperties kakaoOAuthProperties;
    private final WebhookWithdrawalExecutor webhookWithdrawalExecutor;

    /** 어드민 키·앱 ID 검증 후 탈퇴 동기화. 검증·처리 실패 모두 로그만 남기고 정상 반환한다. */
    public void receive(String authorization, String appId, String userId, String referrerType) {
        if (!hasValidAdminKey(authorization) || !kakaoOAuthProperties.appId().equals(appId)
                || userId == null || userId.isBlank()) {
            log.warn("카카오 웹훅 검증 실패 — 상태 변경 없이 무시. app_id={}, user_id={}, referrer_type={}",
                    sanitize(appId), sanitize(userId), sanitize(referrerType));
            return;
        }
        try {
            WebhookWithdrawalExecutor.Result result = webhookWithdrawalExecutor.withdrawIfActive(userId);
            log.info("카카오 웹훅 탈퇴 동기화 — result={}, user_id={}, referrer_type={}",
                    result, sanitize(userId), sanitize(referrerType));
        } catch (Exception e) {
            log.error("카카오 웹훅 탈퇴 동기화 실패 — 재전송이 없으므로 수동 조치 필요(PRD 운영 절차). "
                    + "user_id={}, referrer_type={}", sanitize(userId), sanitize(referrerType), e);
        }
    }

    /** 어드민 키 비교는 상수 시간으로 — 응답 시간 부채널로 키가 유추되지 않게 한다. */
    private boolean hasValidAdminKey(String authorization) {
        if (authorization == null || !authorization.startsWith(AUTHORIZATION_PREFIX)) {
            return false;
        }
        byte[] received = authorization.substring(AUTHORIZATION_PREFIX.length())
                .getBytes(StandardCharsets.UTF_8);
        byte[] expected = kakaoOAuthProperties.adminKey().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(received, expected);
    }

    /** 외부 입력은 개행 제거·길이 제한 후에만 로그에 남긴다(로그 위조·오염 방지). */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n]", "");
        return cleaned.length() <= MAX_LOG_LENGTH ? cleaned : cleaned.substring(0, MAX_LOG_LENGTH) + "...";
    }
}
