package com.aisw.kkori.global.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그 가명화 설정 ({@code log-masking.*}).
 *
 * <p>{@code hmacKey}는 외부 계정 식별자(카카오 회원번호 등)를 로그에 남길 때 쓰는
 * HMAC 전용 키다. 인증·서명 키와 공유하지 않는다(용도 분리 — 키 하나가 새도
 * 다른 체계가 무너지지 않게).
 */
@ConfigurationProperties(prefix = "log-masking")
public record LogMaskingProperties(String hmacKey) {

    /** 빈 설정값은 사용 시점이 아니라 부팅 시점에 실패시킨다(fail-fast). */
    public LogMaskingProperties {
        if (hmacKey == null || hmacKey.isBlank()) {
            throw new IllegalArgumentException("log-masking.hmac-key이(가) 설정되지 않았습니다");
        }
    }
}
