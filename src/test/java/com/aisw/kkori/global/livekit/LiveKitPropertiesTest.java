package com.aisw.kkori.global.livekit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LiveKitProperties} 부팅 시점 fail-fast 검증과 Server API URL 파생 규칙.
 */
class LiveKitPropertiesTest {

    private static final String URL = "wss://test.invalid";
    private static final String KEY = "test-key";
    private static final String SECRET = "test-secret";
    private static final Duration TTL = Duration.ofHours(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void validPropertiesAreAccepted() {
        assertThatCode(() -> new LiveKitProperties(URL, KEY, SECRET, TTL, TIMEOUT)).doesNotThrowAnyException();
        assertThatCode(() -> new LiveKitProperties("ws://localhost:7880", KEY, SECRET, TTL, TIMEOUT))
                .doesNotThrowAnyException();
    }

    @Test
    void blankOrNullSecretsAreRejected() {
        assertThatThrownBy(() -> new LiveKitProperties(URL, "", SECRET, TTL, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, null, TTL, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(null, KEY, SECRET, TTL, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonWebSocketSchemeIsRejected() {
        assertThatThrownBy(() -> new LiveKitProperties("https://test.invalid", KEY, SECRET, TTL, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemeWithoutHostIsRejected() {
        // 접두사만 맞고 호스트가 없는 값 — 접두사 검사만으로는 통과하던 케이스
        assertThatThrownBy(() -> new LiveKitProperties("wss://", KEY, SECRET, TTL, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties("ws://", KEY, SECRET, TTL, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties("wss:// invalid", KEY, SECRET, TTL, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroOrNegativeTtlIsRejected() {
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, Duration.ZERO, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, Duration.ofSeconds(-1), TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, null, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroOrNegativeApiTimeoutIsRejected() {
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, TTL, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, TTL, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, TTL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretIsNotLeakedInExceptionMessage() {
        String secret = "super-secret-value-should-not-leak";
        // secret이 유효하고 url이 잘못된 경우 — 예외 메시지에 secret이 새지 않아야 한다.
        assertThatThrownBy(() -> new LiveKitProperties("bad-scheme", KEY, secret, TTL, TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
    }

    @Test
    void httpApiUrlDerivesFromWsUrl() {
        // wss → https, ws → http, 포트·경로 보존 (self-host 대비)
        assertThat(new LiveKitProperties("wss://proj.livekit.cloud", KEY, SECRET, TTL, TIMEOUT).httpApiUrl())
                .isEqualTo("https://proj.livekit.cloud");
        assertThat(new LiveKitProperties("ws://localhost:7880", KEY, SECRET, TTL, TIMEOUT).httpApiUrl())
                .isEqualTo("http://localhost:7880");
        assertThat(new LiveKitProperties("wss://sfu.example.com:8443/livekit", KEY, SECRET, TTL, TIMEOUT).httpApiUrl())
                .isEqualTo("https://sfu.example.com:8443/livekit");
    }
}
