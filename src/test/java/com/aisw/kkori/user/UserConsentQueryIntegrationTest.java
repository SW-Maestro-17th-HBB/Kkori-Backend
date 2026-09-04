package com.aisw.kkori.user;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.service.ConsentService;
import com.aisw.kkori.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동의 상태 조회 검증 (PRD {@code docs/requirements/user/consent.md} 기능 3).
 *
 * <p>본 API는 상태 조회 전용이다 — 화면 구성 메타데이터(필수 여부·현재 버전)는 기능 2와
 * 조합해 사용한다. 방어적 활성 확인은 JWT 필터가 HTTP 경로를 먼저 차단하므로 서비스 직접
 * 호출로 검증한다.
 */
class UserConsentQueryIntegrationTest extends AuthIntegrationTestSupport {

    private static final String QUERY_URI = "/api/v1/user/consents";
    private static final String SIGNUP_URI = "/api/v1/auth/signup";
    private static final String ALL_CONSENTS = """
            [
              {"type": "privacy", "agreed": true, "version": 1},
              {"type": "audio_usage", "agreed": true, "version": 1},
              {"type": "resume_usage", "agreed": true, "version": 1},
              {"type": "marketing", "agreed": false}
            ]""";
    private static final String REQUIRED_ONLY = """
            [
              {"type": "privacy", "agreed": true, "version": 1},
              {"type": "audio_usage", "agreed": true, "version": 1},
              {"type": "resume_usage", "agreed": true, "version": 1}
            ]""";

    @Autowired
    private ConsentService consentService;

    @Autowired
    private UserService userService;

    @Test
    @DisplayName("가입 직후 조회하면 제출한 동의 내역이 그대로 반영된다 — 필수 3종 AGREED, 명시적 미동의는 false·버전 기록")
    void reflectsSubmittedConsentsAfterSignup() throws Exception {
        String accessToken = signupAndGetAccessToken("kakao-7001", ALL_CONSENTS);

        getWithBearer(QUERY_URI, accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents.length()").value(4))
                .andExpect(jsonPath("$.data.consents[0].type").value("privacy"))
                .andExpect(jsonPath("$.data.consents[0].agreed").value(true))
                .andExpect(jsonPath("$.data.consents[0].version").value(1))
                .andExpect(jsonPath("$.data.consents[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.consents[3].type").value("marketing"))
                // 명시적 미동의는 WITHDRAWN 행이 존재한다 — agreed=false지만 version·updatedAt이 기록됨
                .andExpect(jsonPath("$.data.consents[3].agreed").value(false))
                .andExpect(jsonPath("$.data.consents[3].version").value(1))
                .andExpect(jsonPath("$.data.consents[3].updatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("가입 시 미제출한 선택 항목은 agreed=false, version·updatedAt=null로 반환되고 4항목은 유지된다")
    void unsubmittedOptionalTypeIsNullState() throws Exception {
        String accessToken = signupAndGetAccessToken("kakao-7002", REQUIRED_ONLY);

        getWithBearer(QUERY_URI, accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents.length()").value(4))
                .andExpect(jsonPath("$.data.consents[3].type").value("marketing"))
                .andExpect(jsonPath("$.data.consents[3].agreed").value(false))
                .andExpect(jsonPath("$.data.consents[3].version").doesNotExist())
                .andExpect(jsonPath("$.data.consents[3].updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("동의→철회→재동의를 거친 항목은 최신 행(id 최대) 기준으로 반환된다")
    void latestRowWins() throws Exception {
        User user = saveUser("kakao-7003");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        userConsentRepository.save(UserConsent.create(user.getId(), ConsentType.MARKETING, true, 1, now));
        userConsentRepository.save(UserConsent.create(user.getId(), ConsentType.MARKETING, false, 1, now));
        userConsentRepository.save(UserConsent.create(user.getId(), ConsentType.MARKETING, true, 1, now));
        String accessToken = accessTokenOf(user);

        getWithBearer(QUERY_URI, accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[3].type").value("marketing"))
                .andExpect(jsonPath("$.data.consents[3].agreed").value(true));
    }

    @Test
    @DisplayName("updatedAt은 최신 행의 기록 시각과 정확히 일치한다 — 실제 가입 경로가 만든 행 기준(시각 절삭 검증 겸함)")
    void updatedAtMatchesLatestRowInstant() throws Exception {
        String accessToken = signupAndGetAccessToken("kakao-7004", ALL_CONSENTS);
        User user = userRepository.findByProviderId("kakao-7004").orElseThrow();
        UserConsent privacyRow = userConsentRepository.findByUserId(user.getId()).stream()
                .filter(consent -> consent.getConsentType() == ConsentType.PRIVACY)
                .findFirst().orElseThrow();

        ResultActions result = getWithBearer(QUERY_URI, accessToken).andExpect(status().isOk());
        JsonNode privacyItem = responseData(result).path("consents").get(0);

        assertThat(privacyItem.path("type").asText()).isEqualTo("privacy");
        assertThat(Instant.parse(privacyItem.path("updatedAt").asText()))
                .isEqualTo(privacyRow.getCreatedAt());
    }

    @Test
    @DisplayName("본인의 이력만 집계된다 — 타 유저의 동의가 응답에 섞이지 않는다")
    void isolatedPerUser() throws Exception {
        String accessToken = signupAndGetAccessToken("kakao-7005", REQUIRED_ONLY);
        User other = saveUser("kakao-7006");
        userConsentRepository.save(UserConsent.create(
                other.getId(), ConsentType.MARKETING, true, 1, Instant.now()));

        getWithBearer(QUERY_URI, accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[3].type").value("marketing"))
                .andExpect(jsonPath("$.data.consents[3].agreed").value(false))
                .andExpect(jsonPath("$.data.consents[3].version").doesNotExist());
    }

    @Test
    @DisplayName("AT 없이 호출하면 401, 무효한(위조) AT도 401이다")
    void unauthorizedWithoutValidToken() throws Exception {
        mockMvc.perform(get(QUERY_URI))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(QUERY_URI).header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴 유저의 조회는 서비스 방어선에서 401로 거부된다 — JWT 필터와 별개의 재확인")
    void deletedUserIsRejectedByServiceGuard() {
        User user = saveUser("kakao-7007");
        userService.withdraw(user.getId());

        assertThatThrownBy(() -> consentService.getMyConsents(user.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("이력이 유형당 수백 행으로 늘어도 findLatestByUserId는 유형별 최신 행 최대 4건만 반환·materialize한다")
    void latestQueryReturnsAtMostFourRows() {
        // 결과 행·엔티티화 상한의 보장이다 — DB 집계 시간이 이력 수와 무관하다는 뜻이 아니다(PRD 기능 3 성능 대리 검증)
        User user = saveUser("kakao-7008");
        Instant now = Instant.now();
        for (int i = 0; i < 200; i++) {
            for (ConsentType type : ConsentType.values()) {
                userConsentRepository.save(UserConsent.create(user.getId(), type, i % 2 == 0, 1, now));
            }
        }

        List<UserConsent> latest = userConsentRepository.findLatestByUserId(user.getId());

        assertThat(latest).hasSize(4);
        long maxId = userConsentRepository.findByUserId(user.getId()).stream()
                .mapToLong(UserConsent::getId).max().orElseThrow();
        assertThat(latest).extracting(UserConsent::getId).contains(maxId);
        // 마지막 회차(i=199)는 홀수라 전 유형 WITHDRAWN — 최신 행 판정까지 함께 확인
        assertThat(latest).allSatisfy(row -> assertThat(row.getAction().name()).isEqualTo("WITHDRAWN"));
    }

    // ── 헬퍼 ──

    private String signupAndGetAccessToken(String providerId, String consentsJson) throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken(providerId, null, null);
        ResultActions result = postJson(SIGNUP_URI,
                "{\"signupToken\":\"%s\",\"consents\":%s}".formatted(signupToken, consentsJson));
        result.andExpect(status().isCreated());
        return responseData(result).path("accessToken").asText();
    }

    private String accessTokenOf(User user) {
        return tokenService.issueTokenPair(user.getId()).accessToken();
    }

    private ResultActions getWithBearer(String uri, String accessToken) throws Exception {
        return mockMvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }
}
