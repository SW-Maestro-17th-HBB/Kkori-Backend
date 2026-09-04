package com.aisw.kkori.report.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.report.api.ReportApi;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.dto.ReportDetailResponse;
import com.aisw.kkori.report.dto.ReportRegenerateResponse;
import com.aisw.kkori.report.dto.ReportStatsResponse;
import com.aisw.kkori.global.response.PageResponse;
import com.aisw.kkori.report.dto.ReportStatusResponse;
import com.aisw.kkori.report.dto.ReportSummaryResponse;
import com.aisw.kkori.report.dto.ReportTimelineResponse;
import com.aisw.kkori.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController implements ReportApi {

    private final ReportService reportService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ReportSummaryResponse>>> getList(
            @AuthenticationPrincipal Long userId,
            // enum 변환 실패는 GlobalExceptionHandler의 타입 불일치 핸들러가 400(C002)으로 변환한다
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                reportService.getList(userId, status, sort, order, page, size)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<ReportStatsResponse>> getStats(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getStats(userId)));
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> getDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId
    ) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getDetail(userId, reportId)));
    }

    @GetMapping("/{reportId}/timeline")
    public ResponseEntity<ApiResponse<ReportTimelineResponse>> getTimeline(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId
    ) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getTimeline(userId, reportId)));
    }

    @PostMapping("/{reportId}/retry")
    public ResponseEntity<ApiResponse<ReportRegenerateResponse>> regenerate(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId
    ) {
        return ResponseEntity.ok(ApiResponse.success(reportService.regenerate(userId, reportId)));
    }

    @GetMapping("/{reportId}/status")
    public ResponseEntity<ApiResponse<ReportStatusResponse>> getStatus(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId
    ) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getStatus(userId, reportId)));
    }
}
