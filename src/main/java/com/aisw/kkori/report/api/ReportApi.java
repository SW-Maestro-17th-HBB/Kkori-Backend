package com.aisw.kkori.report.api;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.report.dto.ReportDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/** 리포트 API 문서화 인터페이스 — 컨트롤러는 얇게 유지하고 Swagger 애너테이션은 여기에 둔다. */
@Tag(name = "Report", description = "면접 리포트 조회 API")
public interface ReportApi {

    @Operation(
            summary = "리포트 상세 조회",
            description = """
                    완성(COMPLETED)된 리포트의 상세를 조회한다 — 총평, 축별 점수(전달력 미평가 시 null),
                    종합 점수, 질문 수, 약점 태그 요약, 개선 과제(답변별 과제를 질문 순서대로 수집),
                    AI 분석 한계 안내 문구. 답변별 피드백은 타임라인 API가 담당한다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인의 리포트(RP002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "리포트 없음(RP001)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "생성 진행 중(RP003)·생성 실패 상태(RP004 — 재생성 필요)"),
    })
    ResponseEntity<ApiResponse<ReportDetailResponse>> getDetail(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "리포트 ID", required = true) Long reportId
    );
}
