package com.aisw.kkori.global.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 어드민 키 unlink HTTP 계약 (PRD deletion.md 기능 4 — 인터페이스·응답 해석). */
@RestClientTest(KakaoAdminUnlinkClient.class)
@EnableConfigurationProperties(KakaoOAuthProperties.class)
class KakaoAdminUnlinkClientTest {

    @Autowired
    private KakaoAdminUnlinkClient client;

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private KakaoOAuthProperties properties;

    @Test
    @DisplayName("어드민 키 헤더와 target_id_type=user_id·target_id 폼으로 호출하고 2xx면 UNLINKED")
    void unlinkSuccess() {
        server.expect(requestTo(properties.unlinkUri()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.adminKey()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of("target_id_type", "user_id", "target_id", "123456789")))
                .andRespond(withSuccess("{\"id\": 123456789}", MediaType.APPLICATION_JSON));

        assertThat(client.unlink("123456789")).isEqualTo(KakaoUnlinkClient.Outcome.UNLINKED);
    }

    @Test
    @DisplayName("400 + code -101(앱에 연결되지 않은 사용자)은 이미 해제된 것으로 보고 ALREADY_UNLINKED")
    void notLinkedUserIsTreatedAsAlreadyUnlinked() {
        server.expect(requestTo(properties.unlinkUri()))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}"));

        assertThat(client.unlink("123456789")).isEqualTo(KakaoUnlinkClient.Outcome.ALREADY_UNLINKED);
    }

    @ParameterizedTest(name = "http {0} / body {1}")
    @CsvSource(delimiter = '|', value = {
            "400|{\"msg\":\"bad request\",\"code\":-2}",
            "401|{\"msg\":\"invalid app key\",\"code\":-401}",
            "500|internal error",
            "400|",
    })
    @DisplayName("-101이 아닌 4xx·5xx·비JSON 오류는 KakaoUnlinkException으로 실패한다 (재시도 대상)")
    void otherErrorsFail(int status, String body) {
        server.expect(requestTo(properties.unlinkUri()))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body == null ? "" : body));

        assertThatThrownBy(() -> client.unlink("123456789"))
                .isInstanceOf(KakaoUnlinkException.class)
                .hasMessageNotContaining("123456789");
    }

    @Test
    @DisplayName("예외 메시지에는 응답 본문(회원번호 포함 가능)을 싣지 않고 HTTP 상태·코드만 남긴다")
    void exceptionMessageOmitsResponseBody() {
        server.expect(requestTo(properties.unlinkUri()))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"msg\":\"user 999888777 forbidden\",\"code\":-402}"));

        assertThatThrownBy(() -> client.unlink("999888777"))
                .isInstanceOf(KakaoUnlinkException.class)
                .hasMessageContaining("http=403")
                .hasMessageContaining("code=-402")
                .hasMessageNotContaining("999888777");
    }
}
