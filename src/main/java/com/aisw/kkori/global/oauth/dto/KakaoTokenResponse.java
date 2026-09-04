package com.aisw.kkori.global.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 카카오 토큰 교환 API 응답. access token 외 필드는 사용하지 않는다. */
public record KakaoTokenResponse(
        @JsonProperty("access_token") String accessToken
) {
}
