package com.aisw.kkori.auth.dto;

/** JWT 토큰 쌍. body(JSON)로만 반환한다. */
public record TokenResponse(String accessToken, String refreshToken) {
}
