package com.aisw.kkori.auth;

import com.aisw.kkori.auth.domain.RefreshToken;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.global.jwt.TokenHasher;
import com.aisw.kkori.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TokenAuthIntegrationTest extends AuthIntegrationTestSupport {

    private static final String LOGOUT_URI = "/api/v1/auth/logout";

    @Test
    @DisplayName("RT는 평문이 아닌 SHA-256 해시로 저장된다")
    void refreshTokenIsStoredAsHash() {
        User user = saveUser("kakao-7001");

        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        RefreshToken stored = refreshTokenRepository.findAll().getFirst();
        assertThat(stored.getTokenHash()).isEqualTo(TokenHasher.sha256Hex(tokens.refreshToken()));
        assertThat(stored.getTokenHash()).isNotEqualTo(tokens.refreshToken());
    }

    @Test
    @DisplayName("발급된 AT로 인증 필요 API(logout) 호출이 성공한다")
    void validAccessTokenAuthorizesRequest() throws Exception {
        User user = saveUser("kakao-7002");
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        postJsonWithBearer(LOGOUT_URI, logoutBody(tokens.refreshToken()), tokens.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("AT 없이 인증 필요 API를 호출하면 401 envelope이 반환된다")
    void missingAccessTokenIsRejected() throws Exception {
        postJson(LOGOUT_URI, logoutBody("any-rt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("무효한 AT는 401로 거부된다")
    void invalidAccessTokenIsRejected() throws Exception {
        postJsonWithBearer(LOGOUT_URI, logoutBody("any-rt"), "garbage-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("탈퇴 처리된 유저의 잔여 AT는 매 요청 deleted_at 검증으로 401이 된다")
    void deletedUserAccessTokenIsRejected() throws Exception {
        User user = saveUser("kakao-7003");
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());
        user.softDelete();
        userRepository.save(user);

        postJsonWithBearer(LOGOUT_URI, logoutBody(tokens.refreshToken()), tokens.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("signup token을 Authorization 헤더의 AT로 쓰면 거부된다")
    void signupTokenAsBearerIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-7004", null, null);

        postJsonWithBearer(LOGOUT_URI, logoutBody("any-rt"), signupToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("허용된 오리진의 CORS preflight 요청이 통과한다")
    void corsPreflightIsAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/auth/kakao")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    private String logoutBody(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }
}
