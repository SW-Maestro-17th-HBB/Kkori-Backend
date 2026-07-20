package com.aisw.kkori.global.livekit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LiveKit 연동 설정 ({@code livekit.*}).
 *
 * <p>{@code apiKey}·{@code apiSecret}은 LiveKit Cloud 프로젝트의 자격증명으로, 서버가
 * 룸 입장용 AccessToken(JWT)을 서명하는 데 쓴다. {@code apiSecret}은 서명에만 사용하고
 * 응답·로그 어디에도 노출하지 않는다.
 *
 * <p>{@code url}은 클라이언트가 접속할 LiveKit 서버 주소로 {@code wss://}(또는 개발용 {@code ws://})
 * 스킴이어야 한다. {@code tokenTtl}은 발급 토큰의 만료 기간이다.
 *
 * <p>잘못된 설정(빈 값, 스킴 오류, 0 이하 TTL)은 토큰 발급 시점이 아니라 부팅 시점에 실패시킨다(fail-fast).
 */
@ConfigurationProperties(prefix = "livekit")
public record LiveKitProperties(
        String url,
        String apiKey,
        String apiSecret,
        Duration tokenTtl
) {

    public LiveKitProperties {
        requireWsUrl(url, "livekit.url");
        requireText(apiKey, "livekit.api-key");
        requireText(apiSecret, "livekit.api-secret");
        requirePositive(tokenTtl, "livekit.token-ttl");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s이(가) 설정되지 않았습니다".formatted(name));
        }
    }

    private static void requireWsUrl(String value, String name) {
        requireText(value, name);
        if (!value.startsWith("wss://") && !value.startsWith("ws://")) {
            throw new IllegalArgumentException(
                    "%s은(는) wss:// 또는 ws:// 스킴이어야 합니다".formatted(name));
        }
    }

    private static void requirePositive(Duration ttl, String name) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("%s은(는) 0보다 큰 기간이어야 합니다".formatted(name));
        }
    }
}
