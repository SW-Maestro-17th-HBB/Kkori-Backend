package com.aisw.kkori.global.oauth;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(KakaoOAuthClient.class)
@EnableConfigurationProperties(KakaoOAuthProperties.class)
class KakaoOAuthClientTest {

    @Autowired
    private KakaoOAuthClient kakaoOAuthClient;

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private KakaoOAuthProperties properties;

    @Test
    @DisplayName("code 교환 시 client 자격과 redirect_uri를 form으로 전달하고 신원을 반환한다")
    void authenticateSuccess() {
        server.expect(requestTo(properties.tokenUri()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "authorization_code",
                        "client_id", properties.clientId(),
                        "client_secret", properties.clientSecret(),
                        "redirect_uri", properties.redirectUri(),
                        "code", "valid-code")))
                .andRespond(withSuccess("{\"access_token\":\"kakao-at\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.userInfoUri()))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-at"))
                .andRespond(withSuccess("""
                        {"id": 123456789, "kakao_account": {"email": "user@example.com", "profile": {"nickname": "홍길동"}}}
                        """, MediaType.APPLICATION_JSON));

        KakaoUserInfo info = kakaoOAuthClient.authenticate("valid-code");

        assertThat(info.providerId()).isEqualTo("123456789");
        assertThat(info.email()).isEqualTo("user@example.com");
        assertThat(info.nickname()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("kakao_account·profile이 없어도(제공 미동의) null로 안전하게 파싱된다")
    void missingAccountFieldsAreNullSafe() {
        server.expect(requestTo(properties.tokenUri()))
                .andRespond(withSuccess("{\"access_token\":\"kakao-at\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.userInfoUri()))
                .andRespond(withSuccess("{\"id\": 99}", MediaType.APPLICATION_JSON));

        KakaoUserInfo info = kakaoOAuthClient.authenticate("valid-code");

        assertThat(info.providerId()).isEqualTo("99");
        assertThat(info.email()).isNull();
        assertThat(info.nickname()).isNull();
    }

    @Test
    @DisplayName("카카오 400(invalid_grant)은 우리 계약대로 KAKAO_AUTH_FAILED(401)로 매핑된다")
    void kakao4xxIsMappedToAuthFailed() {
        server.expect(requestTo(properties.tokenUri()))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_code\":\"KOE320\"}"));

        assertThatThrownBy(() -> kakaoOAuthClient.authenticate("expired-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KAKAO_AUTH_FAILED));
    }

    @Test
    @DisplayName("카카오 5xx는 KAKAO_SERVER_ERROR(500)로 매핑된다")
    void kakao5xxIsMappedToServerError() {
        server.expect(requestTo(properties.tokenUri())).andRespond(withServerError());

        assertThatThrownBy(() -> kakaoOAuthClient.authenticate("any-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KAKAO_SERVER_ERROR));
    }

    @Test
    @DisplayName("사용자 정보 조회의 4xx도 KAKAO_AUTH_FAILED로 매핑된다")
    void userInfo4xxIsMappedToAuthFailed() {
        server.expect(requestTo(properties.tokenUri()))
                .andRespond(withSuccess("{\"access_token\":\"kakao-at\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.userInfoUri()))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> kakaoOAuthClient.authenticate("valid-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KAKAO_AUTH_FAILED));
    }
}
