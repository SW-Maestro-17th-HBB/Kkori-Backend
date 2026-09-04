package com.aisw.kkori.report.api;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.dto.ReportDetailResponse;
import com.aisw.kkori.report.dto.ReportRegenerateResponse;
import com.aisw.kkori.report.dto.ReportStatsResponse;
import com.aisw.kkori.global.response.PageResponse;
import com.aisw.kkori.report.dto.ReportStatusResponse;
import com.aisw.kkori.report.dto.ReportSummaryResponse;
import com.aisw.kkori.report.dto.ReportTimelineResponse;
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
    ResponseEntity<ApiResponse<PageResponse<ReportSummaryResponse>>> getList(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "생성 상태 필터 (PENDING/PROCESSING/COMPLETED/FAILED)") ReportStatus status,
            @Parameter(description = "정렬 키: createdAt(기본) 또는 overallScore") String sort,
            @Parameter(description = "정렬 방향: desc(기본) 또는 asc") String order,
            @Parameter(description = "페이지 번호 (기본 0)") int page,
            @Parameter(description = "페이지 크기 (기본 20)") int size
    );

    @Operation(
            summary = "리포트 통계 조회",
            description = """
                    본인의 완료(COMPLETED) 리포트 전체를 집계한 통계를 반환한다 — KPI(완료 수·평균·최고점),
                    지난달 대비 변화(Asia/Seoul 월 경계, 어느 한쪽이 없으면 null), 점수 추이(완료 시각 오름차순
                    최대 12개), 축별 평균(전달력은 평가된 리포트만 모수), 약점 태그 분포(빈도 내림차순 전체).
                    완료 리포트가 없으면 totalCount 0에 수치는 null, 배열은 빈 배열이다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
    })
    ResponseEntity<ApiResponse<ReportStatsResponse>> getStats(
            @Parameter(hidden = true) Long userId
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

    @Operation(
            summary = "질문-답변 타임라인 조회",
            description = """
                    완성(COMPLETED)된 리포트의 질문-답변 흐름을 질문 단위로 조회한다 — 질문·답변 텍스트와
                    답변별 평가(축별 점수·피드백·약점 태그)를 결합해 발화 시각 오름차순으로 반환한다.
                    questionType(MAIN/TAIL)·parentQuestionNumber는 대본 값 그대로 전달된다(꼬리 소속 표시용).
                    페이지네이션 없음 — 한 세션의 전체 흐름을 한 번에 반환한다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인의 리포트(RP002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "리포트 없음(RP001)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "생성 진행 중(RP003)·생성 실패 상태(RP004 — 재생성 필요)"),
    })
    ResponseEntity<ApiResponse<ReportTimelineResponse>> getTimeline(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "리포트 ID", required = true) Long reportId
    );

    @Operation(
            summary = "리포트 재생성",
            description = """
                    생성 실패(FAILED)한 리포트의 재생성을 요청한다. 이전 런의 텍스트 산출물을 초기화하고
                    PENDING으로 되돌린 뒤 생성 요청을 재발행한다 — Worker가 텍스트 분석만 다시 수행하며,
                    이전 런의 음성 결과(deliveryScore)는 보존·재사용된다. PENDING 복귀는 SSE로 push되지
                    않으므로 이 응답이 유일한 통지다. 같은 리포트에 동시 요청 시 한 건만 처리된다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재생성 요청 성공 — 상태 PENDING 복귀"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인의 리포트(RP002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "리포트 없음(RP001)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "생성 진행 중(RP003)·완성된 리포트(RP005 — 재생성 불가)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "재생성 요청 처리 실패(RP006) — FAILED 유지, 다시 시도 가능"),
    })
    ResponseEntity<ApiResponse<ReportRegenerateResponse>> regenerate(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "리포트 ID", required = true) Long reportId
    );

    @Operation(
            summary = "리포트 생성 상태 조회",
            description = """
                    리포트의 현재 생성 상태를 조회한다 — SSE 유실·재연결 시 동기화용.
                    모든 상태에서 조회 가능하며, failedReason은 FAILED일 때만 값이 있다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인의 리포트(RP002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "리포트 없음(RP001)"),
    })
    ResponseEntity<ApiResponse<ReportStatusResponse>> getStatus(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "리포트 ID", required = true) Long reportId
    );
}
