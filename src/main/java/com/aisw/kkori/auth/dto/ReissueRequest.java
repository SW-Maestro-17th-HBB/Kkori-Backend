package com.aisw.kkori.auth.dto;

/** 토큰 재발급 요청. RT 자체가 인증 수단이며, 누락·불일치는 A007로 처리한다. */
public record ReissueRequest(String refreshToken) {
}
