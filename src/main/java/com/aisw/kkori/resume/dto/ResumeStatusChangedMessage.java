package com.aisw.kkori.resume.dto;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code resume.parse.status.changed} Pub/Sub 채널 메시지 계약 — 이 파일이 이 채널의 유일한 스키마 정의다.
 *
 * <p>발행: Python AI Worker(파이프라인 단계마다 {@link #toMap()} 형태의 필드를 JSON 문자열로 PUBLISH — 모든 값은 문자열)
 * / 구독: Spring 전 인스턴스({@code ResumeStatusEventListener} → SSE 중계). Pub/Sub 채널이라 구독 중이 아닐 때의
 * 메시지는 사라진다(복구는 REST — PRD §3). 2026-09-05 HBB1-332에서 스트림 소비 → Pub/Sub 구독으로 전환.
 * userId는 SSE 사용자별 라우팅의 근거로, Worker가 분석 요청 메시지({@link ResumeParseRequestedMessage})의
 * userId를 그대로 되돌려준다(에코).
 * {@link #from(Map)}이 역직렬화 규칙을, {@link #toMap()}이 직렬화 규칙(테스트에서 Worker 연기용)을 담당한다.
 * 필드 추가·변경 시 Worker와 합의 필요 (언어 경계라 코드 공유 불가 — 이 파일이 계약 문서 역할).
 */
public record ResumeStatusChangedMessage(
        Long resumeId,
        Long userId,
        String status,
        String message
) {

    public static final String CHANNEL = "resume.parse.status.changed";

    /** 계약의 유일한 스키마 정의이므로 잘못된 메시지를 사전 차단한다 (message는 실패 사유 전달용이라 null 허용). */
    public ResumeStatusChangedMessage {
        Objects.requireNonNull(resumeId, "resumeId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
    }

    public static ResumeStatusChangedMessage from(Map<String, String> value) {
        return new ResumeStatusChangedMessage(
                Long.valueOf(value.get("resumeId")),
                Long.valueOf(value.get("userId")),
                value.get("status"),
                value.get("message")
        );
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("resumeId", String.valueOf(resumeId));
        map.put("userId", String.valueOf(userId));
        map.put("status", status);
        map.put("message", message == null ? "" : message);
        return map;
    }
}
