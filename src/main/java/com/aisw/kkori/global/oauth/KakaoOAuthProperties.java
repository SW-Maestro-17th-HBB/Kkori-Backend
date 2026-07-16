package com.aisw.kkori.global.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 OAuth 연동 설정 ({@code kakao.*}).
 *
 * {@code redirectUri}는 프론트가 인가 코드를 받은 콜백 URI로, 카카오가 토큰 교환 시
 * code 발급에 쓰인 URI와 일치하는지 검증하는 데 사용된다.
 *
 * <p>{@code adminKey}·{@code appId}는 연결 해제 웹훅 검증용이다. {@code appId}(숫자 앱 ID)는
 * {@code clientId}(REST API 키)와 서로 다른 값이므로 상호 대용 금지.
 */
@ConfigurationProperties(prefix = "kakao")
public record KakaoOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String tokenUri,
        String userInfoUri,
        String adminKey,
        String appId
) {

    /** 빈 설정값은 카카오 호출 시점이 아니라 부팅 시점에 실패시킨다(fail-fast). */
    public KakaoOAuthProperties {
        requireText(clientId, "kakao.client-id");
        requireText(clientSecret, "kakao.client-secret");
        requireText(redirectUri, "kakao.redirect-uri");
        requireText(tokenUri, "kakao.token-uri");
        requireText(userInfoUri, "kakao.user-info-uri");
        requireText(adminKey, "kakao.admin-key");
        requireText(appId, "kakao.app-id");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s이(가) 설정되지 않았습니다".formatted(name));
        }
    }
}
