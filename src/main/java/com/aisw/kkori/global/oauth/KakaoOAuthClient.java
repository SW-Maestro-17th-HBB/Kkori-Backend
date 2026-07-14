package com.aisw.kkori.global.oauth;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.oauth.dto.KakaoTokenResponse;
import com.aisw.kkori.global.oauth.dto.KakaoUserInfoResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 카카오 인증 서버 연동 — 인가 코드 교환과 사용자 정보 조회.
 *
 * 에러 매핑 주의: 카카오는 만료·재사용된 code에 HTTP 400(invalid_grant)을 주지만,
 * API 계약은 인증 실패를 401 {@code KAKAO_AUTH_FAILED}로 정의한다.
 * 카카오의 상태코드를 그대로 반사하지 않고 {@link ErrorCode}의 status로 변환한다.
 */
@Component
public class KakaoOAuthClient {

    private final RestClient restClient;
    private final KakaoOAuthProperties properties;

    public KakaoOAuthClient(RestClient.Builder restClientBuilder, KakaoOAuthProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    /**
     * 인가 코드로 카카오 인증을 완료하고 사용자 신원을 반환한다.
     *
     * @throws BusinessException 카카오 4xx 응답이면 {@code KAKAO_AUTH_FAILED},
     *                           5xx·통신 오류면 {@code KAKAO_SERVER_ERROR}
     */
    public KakaoUserInfo authenticate(String code) {
        try {
            KakaoTokenResponse token = exchangeToken(code);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw new BusinessException(ErrorCode.KAKAO_SERVER_ERROR);
            }
            KakaoUserInfoResponse userInfo = fetchUserInfo(token.accessToken());
            if (userInfo == null || userInfo.id() == null) {
                throw new BusinessException(ErrorCode.KAKAO_SERVER_ERROR);
            }
            return new KakaoUserInfo(String.valueOf(userInfo.id()), userInfo.email(), userInfo.nickname());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new BusinessException(ErrorCode.KAKAO_SERVER_ERROR);
            }
            throw new BusinessException(ErrorCode.KAKAO_AUTH_FAILED);
        } catch (RestClientException e) {
            // I/O 오류·컨텐츠 타입 불일치·역직렬화 실패 등 나머지 전부 — 카카오 쪽 문제로 분류
            throw new BusinessException(ErrorCode.KAKAO_SERVER_ERROR);
        }
    }

    private KakaoTokenResponse exchangeToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("code", code);

        return restClient.post()
                .uri(properties.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KakaoTokenResponse.class);
    }

    private KakaoUserInfoResponse fetchUserInfo(String kakaoAccessToken) {
        return restClient.get()
                .uri(properties.userInfoUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                .retrieve()
                .body(KakaoUserInfoResponse.class);
    }
}
