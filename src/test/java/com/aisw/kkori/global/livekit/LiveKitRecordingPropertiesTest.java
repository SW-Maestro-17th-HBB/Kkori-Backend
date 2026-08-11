package com.aisw.kkori.global.livekit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LiveKitRecordingProperties} 부팅 시점 fail-fast 검증 ({@link LiveKitPropertiesTest}와 동일 방침).
 */
class LiveKitRecordingPropertiesTest {

    @Test
    void validPropertiesAreAccepted() {
        assertThatCode(() -> new LiveKitRecordingProperties("kkori-rec", "ap-northeast-2"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "bucket={0}, region={1}")
    @CsvSource(value = {
            "'', ap-northeast-2",
            "N/A, ap-northeast-2",
            "kkori-rec, ''",
            "kkori-rec, N/A",
    }, nullValues = "N/A")
    void blankOrNullValuesAreRejected(String bucket, String region) {
        assertThatThrownBy(() -> new LiveKitRecordingProperties(bucket, region))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
