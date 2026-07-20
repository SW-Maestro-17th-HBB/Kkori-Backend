package com.aisw.kkori.global.livekit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LiveKitProperties} 부팅 시점 fail-fast 검증.
 */
class LiveKitPropertiesTest {

    private static final String URL = "wss://test.invalid";
    private static final String KEY = "test-key";
    private static final String SECRET = "test-secret";
    private static final Duration TTL = Duration.ofHours(1);

    @Test
    void validPropertiesAreAccepted() {
        assertThatCode(() -> new LiveKitProperties(URL, KEY, SECRET, TTL)).doesNotThrowAnyException();
        assertThatCode(() -> new LiveKitProperties("ws://localhost:7880", KEY, SECRET, TTL))
                .doesNotThrowAnyException();
    }

    @Test
    void blankOrNullSecretsAreRejected() {
        assertThatThrownBy(() -> new LiveKitProperties(URL, "", SECRET, TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, null, TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(null, KEY, SECRET, TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonWebSocketSchemeIsRejected() {
        assertThatThrownBy(() -> new LiveKitProperties("https://test.invalid", KEY, SECRET, TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroOrNegativeTtlIsRejected() {
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitProperties(URL, KEY, SECRET, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretIsNotLeakedInExceptionMessage() {
        String secret = "super-secret-value-should-not-leak";
        // secret이 유효하고 url이 잘못된 경우 — 예외 메시지에 secret이 새지 않아야 한다.
        assertThatThrownBy(() -> new LiveKitProperties("bad-scheme", KEY, secret, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
    }
}
