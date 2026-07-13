package com.aisw.kkori.auth;

import com.aisw.kkori.global.jwt.JwtProperties;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.repository.UserConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SignupIntegrationTest extends AuthIntegrationTestSupport {

    private static final String SIGNUP_URI = "/api/v1/auth/signup";
    private static final String ALL_CONSENTS = """
            [
              {"type": "privacy", "agreed": true},
              {"type": "audio_usage", "agreed": true},
              {"type": "resume_usage", "agreed": true},
              {"type": "marketing", "agreed": false}
            ]""";

    @MockitoSpyBean
    private UserConsentRepository userConsentRepositorySpy;

    @Test
    @DisplayName("가입 완료 시 users와 동의 기록이 함께 생성되고 토큰 쌍이 발급된다")
    void signupCreatesUserAndConsents() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5001", "user@example.com", "홍길동");

        postJson(SIGNUP_URI, signupBody(signupToken, ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        User user = userRepository.findByProviderId("kakao-5001").orElseThrow();
        assertThat(user.getEmail()).isEqualTo("user@example.com");
        assertThat(user.getName()).isEqualTo("홍길동");

        assertThat(userConsentRepository.findAll())
                .hasSize(4)
                .allSatisfy(consent -> assertThat(consent.getUserId()).isEqualTo(user.getId()))
                .filteredOn(consent -> consent.getAction() == ConsentAction.WITHDRAWN)
                .hasSize(1);
    }

    @Test
    @DisplayName("필수 동의 항목이 하나라도 빠지면 400 MISSING_REQUIRED_CONSENT(A004)이고 계정이 생성되지 않는다")
    void missingRequiredConsentIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5002", null, null);
        String withoutResumeUsage = """
                [
                  {"type": "privacy", "agreed": true},
                  {"type": "audio_usage", "agreed": true},
                  {"type": "resume_usage", "agreed": false}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, withoutResumeUsage))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("A004"));

        assertThat(userRepository.count()).isZero();
        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("위변조된 signup token은 401 INVALID_SIGNUP_TOKEN(A005)로 거부된다")
    void tamperedSignupTokenIsRejected() throws Exception {
        String tampered = jwtTokenProvider.createSignupToken("kakao-5003", null, null) + "x";

        postJson(SIGNUP_URI, signupBody(tampered, ALL_CONSENTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A005"));
    }

    @Test
    @DisplayName("만료된 signup token은 401 INVALID_SIGNUP_TOKEN(A005)로 거부된다")
    void expiredSignupTokenIsRejected() throws Exception {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(new JwtProperties(
                jwtProperties.secret(), jwtProperties.signupSecret(),
                jwtProperties.accessTokenTtl(), jwtProperties.refreshTokenTtl(), Duration.ofSeconds(-10)));
        String expired = expiredProvider.createSignupToken("kakao-5004", null, null);

        postJson(SIGNUP_URI, signupBody(expired, ALL_CONSENTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A005"));
    }

    @Test
    @DisplayName("AT를 signup token 자리에 넣으면 401 INVALID_SIGNUP_TOKEN(A005)로 거부된다")
    void accessTokenAsSignupTokenIsRejected() throws Exception {
        String accessToken = jwtTokenProvider.createAccessToken(1L);

        postJson(SIGNUP_URI, signupBody(accessToken, ALL_CONSENTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A005"));
    }

    @Test
    @DisplayName("동일 provider_id의 중복 가입은 409 ALREADY_REGISTERED(A006)로 거부된다")
    void duplicateSignupIsRejected() throws Exception {
        saveUser("kakao-5005");
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5005", null, null);

        postJson(SIGNUP_URI, signupBody(signupToken, ALL_CONSENTS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("A006"));

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("이메일 제공 미동의 유저도 가입되고 email이 NULL로 저장된다")
    void signupWithoutEmail() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5006", null, null);

        postJson(SIGNUP_URI, signupBody(signupToken, ALL_CONSENTS))
                .andExpect(status().isCreated());

        User user = userRepository.findByProviderId("kakao-5006").orElseThrow();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getName()).isNull();
    }

    @Test
    @DisplayName("동의 저장이 실패하면 계정 생성까지 함께 롤백된다 (한 트랜잭션)")
    void consentFailureRollsBackUserCreation() throws Exception {
        willThrow(new RuntimeException("강제 실패"))
                .given(userConsentRepositorySpy).save(any(UserConsent.class));
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5007", null, null);

        postJson(SIGNUP_URI, signupBody(signupToken, ALL_CONSENTS))
                .andExpect(status().isInternalServerError());

        assertThat(userRepository.count()).isZero();
        assertThat(userConsentRepository.count()).isZero();
    }

    private String signupBody(String signupToken, String consents) {
        return "{\"signupToken\":\"%s\",\"consents\":%s}".formatted(signupToken, consents);
    }
}
