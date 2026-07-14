package com.aisw.kkori.global.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 사용자 정보 조회 API({@code /v2/user/me}) 응답.
 *
 * <p>{@code kakao_account}와 그 하위 필드는 사용자의 항목별 제공 동의에 따라
 * 통째로 빠질 수 있으므로 전 구간 null-safe로 접근한다.
 */
public record KakaoUserInfoResponse(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {

    public String email() {
        if (kakaoAccount == null
                || !Boolean.TRUE.equals(kakaoAccount.isEmailValid())
                || !Boolean.TRUE.equals(kakaoAccount.isEmailVerified())) {
            return null;
        }
        return kakaoAccount.email();
    }

    public String nickname() {
        return kakaoAccount == null || kakaoAccount.profile() == null
                ? null
                : kakaoAccount.profile().nickname();
    }

    public record KakaoAccount(
            String email,
            @JsonProperty("is_email_valid") Boolean isEmailValid,
            @JsonProperty("is_email_verified") Boolean isEmailVerified,
            Profile profile
    ) {
    }

    public record Profile(String nickname) {
    }
}
