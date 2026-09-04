package com.aisw.kkori.global.livekit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 면접 녹음(Egress) S3 출력 설정 ({@code livekit.recording.*} — PRD interview-recording.md
 * Egress 요청 사양).
 *
 * <p>{@code bucket}·{@code region}은 egress가 녹음 파일을 업로드할 S3 목적지다. 자격증명은
 * 두지 않는다 — 요청에 싣지 않고 egress 인스턴스의 IAM Role(기본 자격증명 체인)을 사용한다
 * (실측 검증됨). 실제 objectKey는 이 설정이 아니라 {@code egress_ended} 웹훅의
 * {@code fileResults}에서 읽는다.
 *
 * <p>잘못된 설정(빈 값)은 사용 시점이 아니라 부팅 시점에 실패시킨다(fail-fast — {@code livekit.*}
 * 방침과 동일).
 */
@ConfigurationProperties(prefix = "livekit.recording")
public record LiveKitRecordingProperties(
        String bucket,
        String region
) {

    public LiveKitRecordingProperties {
        requireText(bucket, "livekit.recording.bucket");
        requireText(region, "livekit.recording.region");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s이(가) 설정되지 않았습니다".formatted(name));
        }
    }
}
