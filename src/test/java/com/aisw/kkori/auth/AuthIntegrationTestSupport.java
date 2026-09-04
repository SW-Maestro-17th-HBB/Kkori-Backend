package com.aisw.kkori.auth;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.auth.repository.RefreshTokenRepository;
import com.aisw.kkori.auth.service.TokenService;
import com.aisw.kkori.global.jwt.JwtProperties;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.global.oauth.KakaoOAuthClient;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repository.DeletionLogRepository;
import com.aisw.kkori.user.repository.UserConsentRepository;
import com.aisw.kkori.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 인증 통합 테스트 공통 베이스.
 *
 * <p>카카오 외부 통신은 {@link KakaoOAuthClient}를 현재 mock으로 대체한다 — 판정·토큰 로직 검증에
 * 카카오 HTTP 세부는 불필요하며, HTTP 계층은 {@code KakaoOAuthClientTest}(@RestClientTest)가 다룬다.
 * DB 상태는 테스트마다 초기화한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AuthIntegrationTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected UserConsentRepository userConsentRepository;

    @Autowired
    protected RefreshTokenRepository refreshTokenRepository;

    @Autowired
    protected DeletionLogRepository deletionLogRepository;

    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    @Autowired
    protected JwtProperties jwtProperties;

    @Autowired
    protected TokenService tokenService;

    @MockitoBean
    protected KakaoOAuthClient kakaoOAuthClient;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userConsentRepository.deleteAll();
        deletionLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    protected User saveUser(String providerId) {
        return userRepository.save(User.create(providerId, providerId + "@example.com", "테스터"));
    }

    protected ResultActions postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions postJsonWithBearer(String uri, String body, String bearerToken) throws Exception {
        return mockMvc.perform(post(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected JsonNode responseData(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }
}
