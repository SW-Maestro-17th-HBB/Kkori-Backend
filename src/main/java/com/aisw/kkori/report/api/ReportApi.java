package com.aisw.kkori.report.api;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.dto.ReportDetailResponse;
import com.aisw.kkori.report.dto.ReportListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/** 리포트 API 문서화 인터페이스 — 컨트롤러는 얇게 유지하고 Swagger 애너테이션은 여기에 둔다. */
@Tag(name = "Report", description = "면접 리포트 조회 API")
public interface ReportApi {

    @Operation(
            summary = "리포트 목록 조회",
            description = """
                    본인 리포트 목록을 조회한다. 항목은 스냅샷 기반이라 조인이 없고,
                    생성 중(PENDING/PROCESSING)·실패(FAILED) 리포트도 노출된다(미완성 리포트의 점수·태그 요약은 null).
                    정렬: createdAt(기본, 내림차순) 또는 overallScore — 점수 정렬에서 null(미완성)은 방향과 무관하게 항상 뒤,
                    동점은 생성 시각·id 순서로 고정된다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 status·sort·order·페이지 값(C002)"),
    })
    ResponseEntity<ApiResponse<ReportListResponse>> getList(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "생성 상태 필터 (PENDING/PROCESSING/COMPLETED/FAILED)") ReportStatus status,
            @Parameter(description = "정렬 키: createdAt(기본) 또는 overallScore") String sort,
            @Parameter(description = "정렬 방향: desc(기본) 또는 asc") String order,
            @Parameter(description = "페이지 번호 (기본 0)") int page,
            @Parameter(description = "페이지 크기 (기본 20)") int size
    );

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
