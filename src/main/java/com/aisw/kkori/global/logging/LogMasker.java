package com.aisw.kkori.global.logging;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 외부 계정 식별자의 로그 가명화 — HMAC-SHA256 앞 12 hex 문자.
 *
 * <p>같은 입력은 항상 같은 값이라 로그 상관관계 추적이 유지되고, 키 없이는 원문을
 * 역산할 수 없다. 운영에서 원문 특정이 필요하면 동일 키로 {@code users.provider_id}를
 * HMAC 대조한다(PRD 기능 5 운영 절차 — pgcrypto {@code hmac()} 접두 일치 조회).
 */
@Component
public class LogMasker {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int DIGEST_PREFIX_BYTES = 6; // 12 hex 문자 — 로그 상관용으로 충분

    private final SecretKeySpec keySpec;

    public LogMasker(LogMaskingProperties properties) {
        this.keySpec = new SecretKeySpec(
                properties.hmacKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** null은 null 그대로 — 누락 파라미터가 가명값처럼 보이지 않게 한다. */
    public String mask(String value) {
        if (value == null) {
            return null;
        }
        try {
            // Mac은 스레드 안전하지 않으므로 호출마다 생성한다
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, DIGEST_PREFIX_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("로그 가명화 HMAC 초기화에 실패했습니다", e);
        }
    }
}
