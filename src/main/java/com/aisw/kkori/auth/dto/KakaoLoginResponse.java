package com.aisw.kkori.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 카카오 로그인 응답 (oauth.md §소셜 로그인).
 *
 * <ul>
 *   <li>기존 유저: {@code isNewUser=false}·{@code isRestored=false}·{@code accessToken}·{@code refreshToken}</li>
 *   <li>신규 유저: {@code isNewUser=true}·{@code signupToken}</li>
 *   <li>복구 대상: {@code isNewUser=false}·{@code isRestored=true}·{@code signupToken} —
 *       즉시 복구하지 않고 재동의 제출({@code /auth/signup}) 시점에 복구가 성립한다</li>
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

    public static KakaoLoginResponse loggedIn(TokenResponse tokens) {
        return new KakaoLoginResponse(false, false, tokens.accessToken(), tokens.refreshToken(), null);
    }

    public static KakaoLoginResponse newUser(String signupToken) {
        return new KakaoLoginResponse(true, null, null, null, signupToken);
    }

    /** 유예 내 탈퇴 계정 — 복구용 signup token만 발급하고 계정은 변경하지 않는다. */
    public static KakaoLoginResponse restoreRequired(String signupToken) {
        return new KakaoLoginResponse(false, true, null, null, signupToken);
    }
}
