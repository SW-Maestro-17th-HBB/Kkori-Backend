package com.aisw.kkori.resume.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ResumePhysicalDeleteProperties} 부팅 시점 fail-fast 검증 (PRD deletion.md 기능 5 설정). */
class ResumePhysicalDeletePropertiesTest {

    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private static final Duration DELAY = Duration.ofMinutes(10);

    @Test
    @DisplayName("양의 기간이면 허용된다")
    void validPropertiesAreAccepted() {
        assertThatCode(() -> new ResumePhysicalDeleteProperties(INTERVAL, DELAY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("주기·지연이 null·0·음수면 거부된다")
    void nonPositiveDurationsAreRejected() {
        assertThatThrownBy(() -> new ResumePhysicalDeleteProperties(null, DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resume.physical-delete-interval");
        assertThatThrownBy(() -> new ResumePhysicalDeleteProperties(INTERVAL, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resume.physical-delete-delay");
        assertThatThrownBy(() -> new ResumePhysicalDeleteProperties(Duration.ofSeconds(-1), DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resume.physical-delete-interval");
    }
}
