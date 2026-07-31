package com.aisw.kkori.report.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 리포트 SSE 문서화 인터페이스. */
@Tag(name = "Report", description = "면접 리포트 조회 API")
public interface ReportSseApi {

    @Operation(
            summary = "리포트 생성 상태 SSE 구독",
            description = """
                    인증된 사용자 본인 리포트의 생성 상태 변경만 실시간으로 구독한다 (text/event-stream).
                    이벤트 타입: REPORT_GENERATION_STATUS_CHANGED / REPORT_GENERATION_COMPLETED / REPORT_GENERATION_FAILED,
                    data는 단일 스키마 {reportId, status, message}.
                    PENDING은 push되지 않는다 — 이벤트는 PROCESSING부터 흐르고, PENDING은 REST 동기화로 인지한다.
                    연결이 끊긴 동안의 이벤트는 재전송되지 않으므로 재연결 시 REST 조회로 상태를 동기화해야 한다.
                    주의: 브라우저 표준 EventSource는 Authorization 헤더를 지원하지 않으므로
                    fetch 기반 SSE 클라이언트(예: @microsoft/fetch-event-source)를 사용해야 한다.
                    """
    )
    SseEmitter subscribe(@Parameter(hidden = true) Long userId);
}
