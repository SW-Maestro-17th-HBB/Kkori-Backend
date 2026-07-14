package com.aisw.kkori.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 카카오 로그인 응답.
 *
 * <ul>
 *   <li>기존·복구 유저: {@code isNewUser}·{@code isRestored}·{@code accessToken}·{@code refreshToken}</li>
 *   <li>신규 유저: {@code isNewUser}·{@code signupToken}</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KakaoLoginResponse(
        boolean isNewUser,
        Boolean isRestored,
        String accessToken,
        String refreshToken,
        String signupToken
) {

    public static KakaoLoginResponse loggedIn(boolean restored, TokenResponse tokens) {
        return new KakaoLoginResponse(false, restored, tokens.accessToken(), tokens.refreshToken(), null);
    }

    public static KakaoLoginResponse newUser(String signupToken) {
        return new KakaoLoginResponse(true, null, null, null, signupToken);
    }
}
