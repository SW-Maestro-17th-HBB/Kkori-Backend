package com.aisw.kkori.global.livekit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Set;

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
 * <p>{@code apiTimeout}은 서버가 LiveKit Server API(룸 생성·삭제)를 호출할 때의 연결·응답
 * 타임아웃이다. 룸 생성은 user 행 잠금을 보유한 채 일어나는 외부 왕복이라, 이 값이 잠금 보유
 * 시간의 상한이 된다 — LiveKit 지연·장애가 동일 유저의 다른 경로를 길게 막지 못하게 짧게 유지한다.
 *
 * <p>잘못된 설정(빈 값, 스킴 오류, 0 이하 기간)은 사용 시점이 아니라 부팅 시점에 실패시킨다(fail-fast).
 */
@ConfigurationProperties(prefix = "livekit")
public record LiveKitProperties(
        String url,
        String apiKey,
        String apiSecret,
        Duration tokenTtl,
        Duration apiTimeout
) {

    public LiveKitProperties {
        requireWsUrl(url, "livekit.url");
        requireText(apiKey, "livekit.api-key");
        requireText(apiSecret, "livekit.api-secret");
        requirePositive(tokenTtl, "livekit.token-ttl");
        requirePositive(apiTimeout, "livekit.api-timeout");
    }

    /**
     * Server API(REST)용 HTTP 엔드포인트 — 클라이언트 접속 URL({@code wss://})에서 파생한다
     * ({@code wss→https}, {@code ws→http}). LiveKit Cloud·self-host 모두 같은 호스트에서 두
     * 프로토콜을 서비스하므로 별도 설정값을 두지 않는다. 포트·경로는 보존한다(self-host 대비).
     */
    public String httpApiUrl() {
        String scheme = url.toLowerCase().startsWith("wss") ? "https" : "http";
        return scheme + url.substring(url.indexOf("://"));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s이(가) 설정되지 않았습니다".formatted(name));
        }
    }

    private static final Set<String> WS_SCHEMES = Set.of("ws", "wss");

    private static void requireWsUrl(String value, String name) {
        requireText(value, name);
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("%s이(가) 올바른 URI가 아닙니다".formatted(name));
        }
        if (uri.getScheme() == null || !WS_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            throw new IllegalArgumentException(
                    "%s은(는) wss:// 또는 ws:// 스킴이어야 합니다".formatted(name));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("%s에 호스트가 없습니다".formatted(name));
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("%s은(는) 0보다 큰 기간이어야 합니다".formatted(name));
        }
    }
}
