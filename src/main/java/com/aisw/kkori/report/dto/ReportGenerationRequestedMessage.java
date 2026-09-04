package com.aisw.kkori.report.dto;

import java.util.Map;
import java.util.Objects;

/**
 * {@code report.generation.requested} 스트림 메시지 계약 — Spring 발행 측의 스키마 정의.
 *
 * <p>발행: 면접 도메인 에이전트(세션 정상 종료 시)와 Spring(FAILED 재생성 API — 별도 재생성
 * 스트림 없이 같은 생성 요청을 재발행한다, PRD 2026-08-06 변경). 어느 발행자인지는 각 발행자의
 * 로그로 구분한다. 소비: Python AI Worker(XREADGROUP).
 *
 * <p>필드는 sessionId 하나다(2026-07-30 확정) — 소유자·이력서는 Worker가 세션 행에서 직접 읽는다.
 * Redis Stream 필드는 문자열만 허용하므로 {@link #toMap()}이 직렬화 규칙을 담당한다.
 * 필드 추가·변경 시 발행자·소비자 전원과 합의 필요 (언어 경계라 코드 공유 불가).
 */
public record ReportGenerationRequestedMessage(
        Long sessionId
) {

    public static final String STREAM_KEY = "report.generation.requested";

    /** 잘못된 메시지가 스트림에 실리기 전에 차단한다. */
    public ReportGenerationRequestedMessage {
        Objects.requireNonNull(sessionId, "sessionId");
    }

    public Map<String, String> toMap() {
        return Map.of("sessionId", String.valueOf(sessionId));
    }
}
