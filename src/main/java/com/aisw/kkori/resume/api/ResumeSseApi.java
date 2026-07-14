package com.aisw.kkori.resume.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 이력서 SSE 문서화 인터페이스. */
@Tag(name = "Resume", description = "이력서 업로드·분석 API")
public interface ResumeSseApi {

    @Operation(
            summary = "이력서 분석 상태 SSE 구독",
            description = """
                    인증된 사용자 본인 이력서의 분석 상태 변경만 실시간으로 구독한다 (text/event-stream).
                    이벤트 타입: RESUME_ANALYSIS_STATUS_CHANGED / RESUME_ANALYSIS_COMPLETED / RESUME_ANALYSIS_FAILED,
                    data는 단일 스키마 {resumeId, status, message}.
                    연결이 끊긴 동안의 이벤트는 재전송되지 않으므로 재연결 시 REST 조회로 상태를 동기화해야 한다.
                    주의: 브라우저 표준 EventSource는 Authorization 헤더를 지원하지 않으므로
                    fetch 기반 SSE 클라이언트(예: @microsoft/fetch-event-source)를 사용해야 한다.
                    """
    )
    SseEmitter subscribe(@Parameter(hidden = true) Long userId);
}
