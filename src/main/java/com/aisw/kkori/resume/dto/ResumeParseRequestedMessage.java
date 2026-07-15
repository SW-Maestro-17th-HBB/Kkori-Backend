package com.aisw.kkori.resume.dto;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code resume.parse.requested} 스트림 메시지 계약 — 이 파일이 이 스트림의 유일한 스키마 정의다.
 *
 * <p>발행: Spring(업로드·재분석) / 소비: Python AI Worker(XREADGROUP).
 * Redis Stream 필드는 문자열만 허용하므로 {@link #toMap()}이 직렬화 규칙을 담당한다.
 * 필드 추가·변경 시 Worker와 합의 필요 (언어 경계라 코드 공유 불가 — 이 파일이 계약 문서 역할).
 */
public record ResumeParseRequestedMessage(
        Long resumeId,
        Long userId,
        String bucket,
        String objectKey
) {

    public static final String STREAM_KEY = "resume.parse.requested";

    /** 계약의 유일한 스키마 정의이므로 잘못된 메시지가 스트림에 실리기 전에 차단한다. */
    public ResumeParseRequestedMessage {
        Objects.requireNonNull(resumeId, "resumeId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(objectKey, "objectKey");
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("resumeId", String.valueOf(resumeId));
        map.put("userId", String.valueOf(userId));
        map.put("bucket", bucket);
        map.put("objectKey", objectKey);
        return map;
    }
}
