package com.aisw.kkori.report.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.report.api.ReportApi;
import com.aisw.kkori.report.dto.ReportDetailResponse;
import com.aisw.kkori.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController implements ReportApi {

    private final ReportService reportService;

    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> getDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId
    ) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getDetail(userId, reportId)));
    }
}
