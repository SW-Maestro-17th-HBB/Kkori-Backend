package com.aisw.kkori.user;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동의서 버전 인상 시나리오 검증 (PRD {@code docs/requirements/user/consent.md} 기능 1).
 *
 * <p>marketing만 v2로 오버라이드해 항목별 버전이 독립적으로 반영·대조·기록됨을 확인한다.
 * 별도 컨텍스트를 포크하므로(properties 오버라이드) 인상 시나리오는 이 클래스에 모아 비용을
 * 한 번만 치른다.
 */
@SpringBootTest(properties = "consent.versions.marketing=2")
class ConsentVersionOverrideTest extends AuthIntegrationTestSupport {

    private static final String SIGNUP_URI = "/api/v1/auth/signup";

    @Test
    @DisplayName("카탈로그는 인상된 설정 버전을 반영한다 — marketing만 2, 나머지는 1")
    void catalogReflectsBumpedVersion() throws Exception {
        mockMvc.perform(get("/api/v1/consents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[0].type").value("privacy"))
                .andExpect(jsonPath("$.data.consents[0].version").value(1))
                .andExpect(jsonPath("$.data.consents[3].type").value("marketing"))
                .andExpect(jsonPath("$.data.consents[3].version").value(2));
    }

    @Test
    @DisplayName("새 버전으로 제출한 AGREED는 새 버전으로 기록된다")
    void signupRecordsBumpedVersion() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-9401", null, null);
        String consents = """
                [
                  {"type": "privacy", "agreed": true, "version": 1},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1},
                  {"type": "marketing", "agreed": true, "version": 2}
                ]""";

        postJson(SIGNUP_URI, "{\"signupToken\":\"%s\",\"consents\":%s}".formatted(signupToken, consents))
                .andExpect(status().isCreated());

        User user = userRepository.findByProviderId("kakao-9401").orElseThrow();
        assertThat(userConsentRepository.findByUserId(user.getId()))
                .filteredOn(consent -> consent.getConsentType() == ConsentType.MARKETING)
                .singleElement()
                .satisfies(marketing -> {
                    assertThat(marketing.getAction()).isEqualTo(ConsentAction.AGREED);
                    assertThat(marketing.getVersion()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("인상 전 화면에서의 구버전 제출은 409 U005로 걸러지고 아무것도 생성되지 않는다")
    void staleVersionSignupIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-9402", null, null);
        String staleConsents = """
                [
                  {"type": "privacy", "agreed": true, "version": 1},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1},
                  {"type": "marketing", "agreed": true, "version": 1}
                ]""";

        postJson(SIGNUP_URI, "{\"signupToken\":\"%s\",\"consents\":%s}".formatted(signupToken, staleConsents))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("U005"));

        assertThat(userRepository.count()).isZero();
        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("AGREED 상태 항목에 새 버전으로 agreed=true 요청 시 새 버전의 AGREED가 append된다 — 새 버전 재동의")
    void reconsentToNewVersionAppends() throws Exception {
        User user = saveUser("kakao-9403");
        userConsentRepository.save(UserConsent.create(user.getId(), ConsentType.MARKETING, true, 1,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        String accessToken = tokenService.issueTokenPair(user.getId()).accessToken();

        mockMvc.perform(put("/api/v1/user/consents/marketing")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agreed\": true, \"version\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[3].version").value(2));

        assertThat(userConsentRepository.findByUserId(user.getId()))
                .hasSize(2)
                .extracting(UserConsent::getVersion)
                .containsExactly(1, 2);
    }
}
