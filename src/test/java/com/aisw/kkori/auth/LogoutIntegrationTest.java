package com.aisw.kkori.auth;

import com.aisw.kkori.auth.domain.RefreshToken;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.global.jwt.TokenHasher;
import com.aisw.kkori.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LogoutIntegrationTest extends AuthIntegrationTestSupport {

    private static final String LOGOUT_URI = "/api/v1/auth/logout";

    @Test
    @DisplayName("본인 RT 로그아웃 시 revoked_at이 기록되고 replaced_by는 NULL로 남는다")
    void logoutRevokesOwnToken() throws Exception {
        User user = saveUser("kakao-9001");
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        postJsonWithBearer(LOGOUT_URI, logoutBody(tokens.refreshToken()), tokens.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        RefreshToken revoked = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(tokens.refreshToken())).orElseThrow();
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getReplacedByTokenHash()).isNull();
    }

    @Test
    @DisplayName("같은 RT로 반복 로그아웃해도 항상 200을 반환한다 (멱등)")
    void logoutIsIdempotent() throws Exception {
        User user = saveUser("kakao-9002");
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        postJsonWithBearer(LOGOUT_URI, logoutBody(tokens.refreshToken()), tokens.accessToken())
                .andExpect(status().isOk());
        postJsonWithBearer(LOGOUT_URI, logoutBody(tokens.refreshToken()), tokens.accessToken())
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("타인 소유 RT로 로그아웃해도 200을 반환하고 해당 RT는 폐기되지 않는다")
    void otherUsersTokenIsNotRevoked() throws Exception {
        User me = saveUser("kakao-9003");
        User other = saveUser("kakao-9004");
        TokenResponse myTokens = tokenService.issueTokenPair(me.getId());
        TokenResponse otherTokens = tokenService.issueTokenPair(other.getId());

        postJsonWithBearer(LOGOUT_URI, logoutBody(otherTokens.refreshToken()), myTokens.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        RefreshToken otherToken = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(otherTokens.refreshToken())).orElseThrow();
        assertThat(otherToken.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("DB에 없는 RT로 로그아웃해도 200을 반환한다 (존재 여부 비노출)")
    void unknownTokenStillReturnsOk() throws Exception {
        User user = saveUser("kakao-9005");
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        postJsonWithBearer(LOGOUT_URI, logoutBody("unknown-refresh-token"), tokens.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("refreshToken이 빈 malformed 요청은 400으로 거부된다")
    void blankRefreshTokenIsRejected() throws Exception {
        User user = saveUser("kakao-9006");
        TokenResponse tokens = tokenService.issueTokenPair(user.getId());

        postJsonWithBearer(LOGOUT_URI, logoutBody(""), tokens.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    private String logoutBody(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }
}
