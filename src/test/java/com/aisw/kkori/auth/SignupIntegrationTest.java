package com.aisw.kkori.auth;

import com.aisw.kkori.global.jwt.TokenType;
import com.aisw.kkori.user.domain.ConsentAction;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.repository.UserConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SignupIntegrationTest extends AuthIntegrationTestSupport {

    private static final String SIGNUP_URI = "/api/v1/auth/signup";
    private static final String ALL_CONSENTS = """
            [
              {"type": "privacy", "agreed": true, "version": 1},
              {"type": "audio_usage", "agreed": true, "version": 1},
              {"type": "resume_usage", "agreed": true, "version": 1},
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
                  {"type": "privacy", "agreed": true, "version": 1},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": false}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, withoutResumeUsage))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("A004"));

        assertThat(userRepository.count()).isZero();
        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("동의 배열에 null 항목이 있으면 400 INVALID_INPUT_VALUE(C002)로 거부되고 계정이 생성되지 않는다")
    void nullConsentItemIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5008", null, null);
        String withNullItem = """
                [
                  null,
                  {"type": "privacy", "agreed": true, "version": 1},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, withNullItem))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("동의 항목의 type이 null이면 400 INVALID_INPUT_VALUE(C002)로 거부된다")
    void nullConsentTypeIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5009", null, null);
        String withNullType = """
                [
                  {"type": null, "agreed": true, "version": 1},
                  {"type": "privacy", "agreed": true, "version": 1},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, withNullType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("동일 type이 중복된 consents는 400 INVALID_INPUT_VALUE(C002)로 거부된다 — last-wins 금지")
    void duplicateConsentTypeIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5010", null, null);
        String withDuplicateMarketing = """
                [
                  {"type": "privacy", "agreed": true, "version": 1},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1},
                  {"type": "marketing", "agreed": true, "version": 1},
                  {"type": "marketing", "agreed": false}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, withDuplicateMarketing))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        assertThat(userRepository.count()).isZero();
        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("agreed가 누락된 동의 항목은 400 INVALID_INPUT_VALUE(C002)로 거부된다")
    void missingAgreedIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5011", null, null);
        String withMissingAgreed = """
                [
                  {"type": "privacy", "version": 1},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, withMissingAgreed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("agreed=true인데 version이 누락되면 400 INVALID_INPUT_VALUE(C002)로 거부된다")
    void missingVersionOnAgreedIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5012", null, null);
        String withoutVersion = """
                [
                  {"type": "privacy", "agreed": true},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, withoutVersion))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("서버 현재 버전과 다른 version 제출은 409 CONSENT_VERSION_MISMATCH(U005)이고 아무것도 생성되지 않는다")
    void versionMismatchIsRejected() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5013", null, null);
        String staleVersion = """
                [
                  {"type": "privacy", "agreed": true, "version": 99},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, staleVersion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("U005"));

        assertThat(userRepository.count()).isZero();
        assertThat(userConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("버전 불일치와 필수 동의 누락이 함께 있으면 400이 아닌 409로 거부된다 — 버전 검증 선행")
    void versionCheckPrecedesRequiredConsentCheck() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5014", null, null);
        String staleAndMissing = """
                [
                  {"type": "privacy", "agreed": true, "version": 99},
                  {"type": "audio_usage", "agreed": true, "version": 1}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, staleAndMissing))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("U005"));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("가입 동의 기록의 버전은 전 항목 제출·설정 버전(1)과 일치한다")
    void recordedVersionsFollowContract() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5015", null, null);

        postJson(SIGNUP_URI, signupBody(signupToken, ALL_CONSENTS))
                .andExpect(status().isCreated());

        User user = userRepository.findByProviderId("kakao-5015").orElseThrow();
        assertThat(userConsentRepository.findByUserId(user.getId()))
                .hasSize(4)
                .allSatisfy(consent -> assertThat(consent.getVersion()).isEqualTo(1));
    }

    @Test
    @DisplayName("agreed=false 항목의 version은 무시된다 — version=99여도 성공하고 WITHDRAWN은 설정 버전으로 기록된다")
    void versionOnDeclinedItemIsIgnored() throws Exception {
        String signupToken = jwtTokenProvider.createSignupToken("kakao-5016", null, null);
        String declinedWithBogusVersion = """
                [
                  {"type": "privacy", "agreed": true, "version": 1},
                  {"type": "audio_usage", "agreed": true, "version": 1},
                  {"type": "resume_usage", "agreed": true, "version": 1},
                  {"type": "marketing", "agreed": false, "version": 99}
                ]""";

        postJson(SIGNUP_URI, signupBody(signupToken, declinedWithBogusVersion))
                .andExpect(status().isCreated());

        User user = userRepository.findByProviderId("kakao-5016").orElseThrow();
        assertThat(userConsentRepository.findByUserId(user.getId()))
                .filteredOn(consent -> consent.getAction() == ConsentAction.WITHDRAWN)
                .singleElement()
                .satisfies(withdrawn -> assertThat(withdrawn.getVersion()).isEqualTo(1));
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
        // 정상 키로 서명됐지만 이미 만료된 signup token을 직접 만든다
        String expired = Jwts.builder()
                .issuedAt(Date.from(Instant.now().minusSeconds(1200)))
                .expiration(Date.from(Instant.now().minusSeconds(600)))
                .claim("provider_id", "kakao-5004")
                .claim(TokenType.CLAIM_NAME, TokenType.SIGNUP.getValue())
                .signWith(Keys.hmacShaKeyFor(jwtProperties.signupSecret().getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();

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
