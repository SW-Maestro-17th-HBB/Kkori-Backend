package com.aisw.kkori.global.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 OAuth 연동 설정 ({@code kakao.*}).
 *
 * {@code redirectUri}는 프론트가 인가 코드를 받은 콜백 URI로, 카카오가 토큰 교환 시
 * code 발급에 쓰인 URI와 일치하는지 검증하는 데 사용된다.
 */
@ConfigurationProperties(prefix = "kakao")
public record KakaoOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String tokenUri,
        String userInfoUri
) {

    /** 빈 설정값은 카카오 호출 시점이 아니라 부팅 시점에 실패시킨다(fail-fast). */
    public KakaoOAuthProperties {
        requireText(clientId, "kakao.client-id");
        requireText(clientSecret, "kakao.client-secret");
        requireText(redirectUri, "kakao.redirect-uri");
        requireText(tokenUri, "kakao.token-uri");
        requireText(userInfoUri, "kakao.user-info-uri");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s이(가) 설정되지 않았습니다".formatted(name));
        }
    }
}
