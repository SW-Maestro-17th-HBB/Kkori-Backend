package com.aisw.kkori.global.jwt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * JWT의 {@code token_type} claim 값.
 *
 * <p>모든 토큰은 발급 시 자신의 타입을 claim으로 담고, 검증 시 기대 타입과 대조한다 —
 * signup token을 Access Token으로 오용하는 등의 교차 사용을 차단한다.
 */
@Getter
@RequiredArgsConstructor
public enum TokenType {

    ACCESS("access"),
    REFRESH("refresh"),
    SIGNUP("signup"),
    ;

    public static final String CLAIM_NAME = "token_type";

    private final String value;
}
