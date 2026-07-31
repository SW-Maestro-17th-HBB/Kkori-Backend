package com.aisw.kkori;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * LiveKit webhook 서명 요청 구성 유틸 — 발신 형식대로 Authorization 헤더를 만든다:
 * API Secret으로 서명(HS256)한 JWT(issuer=API Key, {@code sha256} claim=바디 SHA-256 base64).
 * Cloud webhook은 공인 URL이 필요해 실이벤트 자동화가 불가하므로, 자동 테스트는 이 구성으로
 * 서명 검증·전이 배선을 검증한다(PRD interview-session-completion.md 기능 1 검증 기준).
 * 서명은 jjwt로 생성한다 — 표준 JWT라 SDK({@code WebhookReceiver})의 검증과 호환된다.
 */
public final class LiveKitWebhookTestSigner {

    private LiveKitWebhookTestSigner() {
    }

    public static String sign(String body, String apiKey, String secret) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return Jwts.builder()
                    .issuer(apiKey)
                    .claim("sha256", Base64.getEncoder().encodeToString(sha))
                    .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                    .compact();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
