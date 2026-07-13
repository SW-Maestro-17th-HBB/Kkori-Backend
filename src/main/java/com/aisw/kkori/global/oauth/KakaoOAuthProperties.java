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
}
