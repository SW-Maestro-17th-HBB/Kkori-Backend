package com.aisw.kkori.session.dto;

import java.util.Map;
import java.util.Objects;

/**
 * {@code report.audio.analysis.requested} 스트림 메시지 계약 — Spring 발행 측의 스키마 정의
 * (PRD interview-recording.md §발행 계약).
 *
 * <p>Kkori-AI {@code worker/src/contract/report.py}의 {@code AudioAnalysisRequested}와 크로스
 * 레포 계약이다 — 필드 추가·변경은 양 레포 합의·동시 반영으로만 한다. 발행: 세션 도메인
 * (녹음 업로드 완료 시). 소비: Python AI Worker(음성 분석 2단계). Redis Stream 필드는 문자열만
 * 허용하므로 {@link #toMap()}이 직렬화 규칙을 담당한다.
 */
public record AudioAnalysisRequestedMessage(
        Long sessionId,
        String bucket,
        String objectKey
) {

    public static final String STREAM_KEY = "report.audio.analysis.requested";

    /** 잘못된 메시지가 스트림에 실리기 전에 차단한다. */
    public AudioAnalysisRequestedMessage {
        Objects.requireNonNull(sessionId, "sessionId");
        requireText(bucket, "bucket");
        requireText(objectKey, "objectKey");
    }

    public Map<String, String> toMap() {
        return Map.of(
                "sessionId", String.valueOf(sessionId),
                "bucket", bucket,
                "objectKey", objectKey);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s이(가) 비어 있습니다".formatted(name));
        }
    }
}
