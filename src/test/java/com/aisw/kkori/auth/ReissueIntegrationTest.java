package com.aisw.kkori.auth;

import com.aisw.kkori.auth.domain.RefreshToken;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.global.jwt.TokenHasher;
import com.aisw.kkori.user.domain.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReissueIntegrationTest extends AuthIntegrationTestSupport {

    private static final String REISSUE_URI = "/api/v1/auth/reissue";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("유효한 RT 재발급 시 새 토큰 쌍이 반환되고 기존 RT에 revoked_at·replaced_by가 기록된다")
    void validReissueRotatesToken() throws Exception {
        User user = saveUser("kakao-8001");
        TokenResponse issued = tokenService.issueTokenPair(user.getId());

        ResultActions result = postJson(REISSUE_URI, reissueBody(issued.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        String newRefreshToken = responseData(result).path("refreshToken").asText();
        assertThat(newRefreshToken).isNotEqualTo(issued.refreshToken());

        RefreshToken old = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(issued.refreshToken())).orElseThrow();
        assertThat(old.getRevokedAt()).isNotNull();
        assertThat(old.getReplacedByTokenHash()).isEqualTo(TokenHasher.sha256Hex(newRefreshToken));
    }

    @Test
    @DisplayName("DB에 없는 RT는 401 RT_NOT_FOUND(A007)로 거부된다")
    void unknownTokenIsRejected() throws Exception {
        postJson(REISSUE_URI, reissueBody("unknown-refresh-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A007"));
    }

    @Test
    @DisplayName("만료된 RT는 401 RT_EXPIRED(A008)로 거부된다")
    void expiredTokenIsRejected() throws Exception {
        User user = saveUser("kakao-8002");
        Instant issuedAt = Instant.now().minus(Duration.ofDays(15)).truncatedTo(ChronoUnit.SECONDS);
        Instant expiredAt = issuedAt.plus(Duration.ofDays(14));
        String jti = UUID.randomUUID().toString();
        String expiredToken = jwtTokenProvider.createRefreshToken(user.getId(), jti, issuedAt, expiredAt);
        refreshTokenRepository.save(RefreshToken.issue(
                user.getId(), TokenHasher.sha256Hex(expiredToken), jti, issuedAt, expiredAt));

        postJson(REISSUE_URI, reissueBody(expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A008"));
    }

    @Test
    @DisplayName("폐기 후 60초 이내 재시도는 새로 발급하지 않고 원래 발급했던 RT를 그대로 반환한다")
    void gracePeriodReturnsOriginalReplacement() throws Exception {
        User user = saveUser("kakao-8003");
        TokenResponse issued = tokenService.issueTokenPair(user.getId());

        ResultActions rotation = postJson(REISSUE_URI, reissueBody(issued.refreshToken()))
                .andExpect(status().isOk());
        String rotatedRefreshToken = responseData(rotation).path("refreshToken").asText();
        long rowCountAfterRotation = refreshTokenRepository.count();

        // 응답 유실을 가정하고 같은 옛 RT로 재시도 (폐기 직후 → Grace Period 내)
        ResultActions retry = postJson(REISSUE_URI, reissueBody(issued.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        JsonNode retryData = responseData(retry);
        assertThat(retryData.path("refreshToken").asText()).isEqualTo(rotatedRefreshToken);
        assertThat(refreshTokenRepository.count()).isEqualTo(rowCountAfterRotation);
    }

    @Test
    @DisplayName("폐기 후 60초 초과 재사용은 탈취로 간주해 유저의 모든 RT를 무효화하고 A009를 반환한다")
    void reuseAfterGracePeriodRevokesAllTokens() throws Exception {
        User user = saveUser("kakao-8004");
        TokenResponse issued = tokenService.issueTokenPair(user.getId());
        postJson(REISSUE_URI, reissueBody(issued.refreshToken())).andExpect(status().isOk());
        rewindRevokedAt(issued.refreshToken(), Duration.ofSeconds(61));

        postJson(REISSUE_URI, reissueBody(issued.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A009"));

        assertThat(refreshTokenRepository.findAll())
                .allSatisfy(rt -> assertThat(rt.getRevokedAt()).isNotNull());
    }

    @Test
    @DisplayName("전체 무효화 이후에는 같은 유저의 다른 기기 RT로도 재발급이 불가능하다")
    void otherDeviceTokenIsAlsoRevokedAfterReuseDetection() throws Exception {
        User user = saveUser("kakao-8005");
        TokenResponse deviceA = tokenService.issueTokenPair(user.getId());
        TokenResponse deviceB = tokenService.issueTokenPair(user.getId());

        postJson(REISSUE_URI, reissueBody(deviceA.refreshToken())).andExpect(status().isOk());
        rewindRevokedAt(deviceA.refreshToken(), Duration.ofSeconds(61));
        postJson(REISSUE_URI, reissueBody(deviceA.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A009"));

        postJson(REISSUE_URI, reissueBody(deviceB.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A009"));
    }

    @Test
    @DisplayName("로그아웃으로 폐기된 RT(replaced_by NULL)는 60초 이내라도 재발급이 거부된다")
    void logoutRevokedTokenGetsNoGracePeriod() throws Exception {
        User user = saveUser("kakao-8006");
        TokenResponse issued = tokenService.issueTokenPair(user.getId());
        tokenService.logout(user.getId(), issued.refreshToken());

        // 방금 폐기됐지만(60초 내) 회전 폐기가 아니므로 Grace Period가 적용되지 않는다
        postJson(REISSUE_URI, reissueBody(issued.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A009"));
    }

    private void rewindRevokedAt(String refreshToken, Duration rewind) {
        jdbcTemplate.update("update refresh_token set revoked_at = ? where token_hash = ?",
                Timestamp.from(Instant.now().minus(rewind)),
                TokenHasher.sha256Hex(refreshToken));
    }

    private String reissueBody(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }
}
