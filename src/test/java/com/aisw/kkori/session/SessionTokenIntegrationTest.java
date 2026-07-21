package com.aisw.kkori.session;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/sessions} 인가·응답 계약 통합 테스트.
 *
 * <p>실제 Cloud 접속은 검증하지 않는다(테스트 프로퍼티는 연결 불가한 더미). 토큰 서명은
 * 로컬 연산이라 더미 자격증명으로도 발급 자체는 성공하며, 여기서는 인증 게이트와 응답
 * 필드 계약만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionTokenIntegrationTest {

    private static final String SESSIONS_URI = "/api/v1/sessions";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    private String roomOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("livekitRoom").asText();
    }

    @Test
    @DisplayName("인증 유저는 201과 함께 livekitToken·livekitUrl·livekitRoom을 받는다")
    void authenticatedUserReceivesToken() throws Exception {
        User user = userRepository.save(User.create("kakao-9001", "s@example.com", "테스터"));
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());

        mockMvc.perform(post(SESSIONS_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.livekitToken").isNotEmpty())
                .andExpect(jsonPath("$.data.livekitUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.livekitRoom").isNotEmpty());
    }

    @Test
    @DisplayName("연속 두 요청은 서로 다른 roomName을 반환한다")
    void eachRequestReturnsDistinctRoom() throws Exception {
        User user = userRepository.save(User.create("kakao-9002", "d@example.com", "테스터"));
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());

        String first = roomOf(mockMvc.perform(
                post(SESSIONS_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isCreated()));
        String second = roomOf(mockMvc.perform(
                post(SESSIONS_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isCreated()));

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("AT 없이 호출하면 401 C005로 거부된다")
    void missingAccessTokenIsRejected() throws Exception {
        mockMvc.perform(post(SESSIONS_URI))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("무효한 AT는 401로 거부된다")
    void invalidAccessTokenIsRejected() throws Exception {
        mockMvc.perform(post(SESSIONS_URI).header(HttpHeaders.AUTHORIZATION, "Bearer garbage-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }
}
