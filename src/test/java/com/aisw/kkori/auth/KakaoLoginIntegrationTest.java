package com.aisw.kkori.auth;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KakaoLoginIntegrationTest extends AuthIntegrationTestSupport {

    private static final String LOGIN_URI = "/api/v1/auth/kakao";

    @Test
    @DisplayName("기존 유저는 isNewUser=false와 함께 토큰 쌍을 받는다")
    void existingUserReceivesTokens() throws Exception {
        User user = saveUser("kakao-1001");
        given(kakaoOAuthClient.authenticate(anyString()))
                .willReturn(new KakaoUserInfo("kakao-1001", user.getEmail(), user.getName()));

        postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isNewUser").value(false))
                .andExpect(jsonPath("$.data.isRestored").value(false))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.signupToken").doesNotExist());
    }

    @Test
    @DisplayName("신규 유저는 signupToken만 받고 계정은 생성되지 않는다")
    void newUserReceivesSignupTokenOnly() throws Exception {
        given(kakaoOAuthClient.authenticate(anyString()))
                .willReturn(new KakaoUserInfo("kakao-2002", "new@example.com", "신규"));

        postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(true))
                .andExpect(jsonPath("$.data.signupToken").isNotEmpty())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.isRestored").doesNotExist());

        assertThat(userRepository.count()).isZero();
    }

    // TODO(HBB1-245): 복구 계약 교체(signupToken + 재동의 제출 시 복구 — oauth.md §소셜 로그인) 시
    //  이 테스트를 signupToken 기대값으로 재작성. 그때까지는 현행 구현(즉시 복구)의 임시 시맨틱을 문서화한다.
    //  스토리 브랜치 통합 전략상 이 시맨틱은 develop에 도달하지 않는다.
    @Test
    @DisplayName("탈퇴 유예 중 유저는 복구되고 isRestored=true와 토큰을 받는다")
    void softDeletedUserIsRestored() throws Exception {
        User user = saveUser("kakao-3003");
        user.softDelete(Instant.now());
        userRepository.save(user);
        given(kakaoOAuthClient.authenticate(anyString()))
                .willReturn(new KakaoUserInfo("kakao-3003", user.getEmail(), user.getName()));

        postJson(LOGIN_URI, "{\"code\":\"valid-code\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(false))
                .andExpect(jsonPath("$.data.isRestored").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        User restored = userRepository.findByProviderId("kakao-3003").orElseThrow();
        assertThat(restored.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("code가 비어 있으면 400 INVALID_CODE(A001)를 반환한다")
    void blankCodeIsRejected() throws Exception {
        postJson(LOGIN_URI, "{\"code\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("A001"));

        postJson(LOGIN_URI, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("A001"));
    }

    @Test
    @DisplayName("만료·재사용 code는 401 KAKAO_AUTH_FAILED(A002)를 반환한다")
    void kakaoAuthFailure() throws Exception {
        given(kakaoOAuthClient.authenticate(anyString()))
                .willThrow(new BusinessException(ErrorCode.KAKAO_AUTH_FAILED));

        postJson(LOGIN_URI, "{\"code\":\"expired-code\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
    }

    @Test
    @DisplayName("카카오 서버 통신 오류는 500 KAKAO_SERVER_ERROR(A003)를 반환한다")
    void kakaoServerFailure() throws Exception {
        given(kakaoOAuthClient.authenticate(anyString()))
                .willThrow(new BusinessException(ErrorCode.KAKAO_SERVER_ERROR));

        postJson(LOGIN_URI, "{\"code\":\"any-code\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("A003"));
    }
}
