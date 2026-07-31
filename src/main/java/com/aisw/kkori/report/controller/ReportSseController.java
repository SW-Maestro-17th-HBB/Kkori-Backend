package com.aisw.kkori.report.controller;

import com.aisw.kkori.report.api.ReportSseApi;
import com.aisw.kkori.report.service.ReportSseEmitters;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 전용 네임스페이스({@code /sse/**}) — 일반 REST API({@code /api/**})와 경로를 분리한다.
 * SSE는 text/event-stream 스트리밍이므로 ApiResponse 엔벨로프를 적용하지 않는다.
 */
@RestController
@RequestMapping("/sse/v1/reports")
@RequiredArgsConstructor
public class ReportSseController implements ReportSseApi {

    private final ReportSseEmitters emitters;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@AuthenticationPrincipal Long userId) {
        return emitters.add(userId);
    }
}
