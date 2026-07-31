package com.aisw.kkori.report.dto;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code report.status.changed} 스트림 메시지 계약 — 이 파일이 이 스트림의 유일한 스키마 정의다
 * (docs/requirements/report/report.md §1 인터페이스).
 *
 * <p>발행: Python AI Worker(상태 전이 확정 직후 XADD) / 소비: Spring({@code ReportStatusEventListener} → SSE 중계).
 * userId는 SSE 사용자별 라우팅의 근거로, Worker가 리포트 행의 소유자를 에코한다.
 * 발행되는 status는 PROCESSING·COMPLETED·FAILED 3종뿐이다 — PENDING은 로우 생성 직후의 내부
 * 상태라 발행하지 않으며(PRD §5), Worker 쪽 계약 모델이 이를 강제한다.
 * {@link #from(Map)}이 역직렬화 규칙을, {@link #toMap()}이 직렬화 규칙(테스트에서 Worker 연기용)을 담당한다.
 * 필드 추가·변경 시 Worker와 합의 필요 (언어 경계라 코드 공유 불가 — 이 파일이 계약 문서 역할).
 */
public record ReportStatusChangedMessage(
        Long reportId,
        Long userId,
        String status,
        String message
) {

    public static final String STREAM_KEY = "report.status.changed";

    /** 계약의 유일한 스키마 정의이므로 잘못된 메시지를 사전 차단한다 (message는 실패 사유 전달용이라 null 허용). */
    public ReportStatusChangedMessage {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
    }

    public static ReportStatusChangedMessage from(Map<String, String> value) {
        return new ReportStatusChangedMessage(
                Long.valueOf(value.get("reportId")),
                Long.valueOf(value.get("userId")),
                value.get("status"),
                value.get("message")
        );
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("reportId", String.valueOf(reportId));
        map.put("userId", String.valueOf(userId));
        map.put("status", status);
        map.put("message", message == null ? "" : message);
        return map;
    }
}
