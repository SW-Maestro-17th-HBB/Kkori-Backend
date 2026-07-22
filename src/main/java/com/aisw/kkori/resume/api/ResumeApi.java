package com.aisw.kkori.resume.api;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.global.response.PageResponse;
import com.aisw.kkori.resume.dto.ResumeParsedResponse;
import com.aisw.kkori.resume.dto.ResumeParsedUpdateRequest;
import com.aisw.kkori.resume.dto.ResumeReanalyzeResponse;
import com.aisw.kkori.resume.dto.ResumeSummaryResponse;
import com.aisw.kkori.resume.dto.ResumeUploadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 이력서 API 문서화 인터페이스 — 컨트롤러는 얇게 유지하고 Swagger 애너테이션은 여기에 둔다. */
@Tag(name = "Resume", description = "이력서 업로드·분석 API")
public interface ResumeApi {

    @Operation(
            summary = "이력서 PDF 업로드",
            description = """
                    이력서 PDF를 업로드한다. 서버는 파일을 검증(PDF, 10MB, 10페이지)한 뒤 S3에 저장하고
                    비동기 분석을 요청한다. 분석 진행 상태는 SSE(GET /sse/v1/resumes)로 전달된다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "업로드 성공, 분석 대기(UPLOADED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "동일 파일의 활성 이력서가 이미 존재 — 기존 정보 반환(duplicated: true), 아무것도 생성·변경되지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "파일 누락(R001)·형식 오류(R002)·손상 PDF(R004)·페이지 초과(R005)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "파일 크기 초과(R003)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "S3 저장 실패(R006)·분석 요청 실패(R007)"),
    })
    ResponseEntity<ApiResponse<ResumeUploadResponse>> upload(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "업로드할 PDF 파일", required = true) MultipartFile file,
            @Parameter(description = "이력서 표시 이름. 없으면 원본 파일명 사용") String title
    );

    @Operation(
            summary = "이력서 목록 조회",
            description = """
                    본인 이력서 목록을 createdAt 내림차순으로 조회한다. 항목은 UI 소비 최소 필드만 내려주며
                    분석 결과 미리보기는 포함하지 않는다 — 행 펼침 시 GET /{resumeId}/parsed로 조회한다.
                    status 파라미터로 분석 상태 필터링이 가능하다(예: EMBEDDED — 면접 시작 전 선택 화면).
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공 — 페이지 엔벨로프 { content, page, size, totalElements, hasNext }"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "범위를 벗어난 page/size(C002)·잘못된 status 값(R012)"),
    })
    ResponseEntity<ApiResponse<PageResponse<ResumeSummaryResponse>>> getList(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "분석 상태 필터. 미지정 시 전체 조회") String status,
            @Parameter(description = "페이지 번호 (0부터, 기본 0)") int page,
            @Parameter(description = "페이지 크기 (기본 20, 최대 100)") int size
    );

    @Operation(
            summary = "이력서 삭제",
            description = """
                    이력서를 삭제한다(soft delete) — 즉시 목록·조회에서 사라진다.
                    원본(S3)·구조화 데이터·청크·임베딩의 물리 삭제는 후속 배치가 수행한다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인의 이력서(R009)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이력서 없음·이미 삭제됨(R008)"),
    })
    ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "이력서 ID", required = true) Long resumeId
    );

    @Operation(
            summary = "파싱 결과 조회",
            description = "AI 분석이 완료(EMBEDDED)된 이력서의 구조화 결과를 조회한다. 원문 텍스트(rawText)는 제공하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인의 이력서(R009)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이력서 없음(R008)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "분석 진행 중(R010)·분석 실패 상태(R011 — 재분석 필요)"),
    })
    ResponseEntity<ApiResponse<ResumeParsedResponse>> getParsed(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "이력서 ID", required = true) Long resumeId
    );

    @Operation(
            summary = "파싱 결과 수정",
            description = """
                    구조화 결과를 사용자가 수정한다. 저장만 하며 색인에는 반영되지 않는다 —
                    면접 질문 생성에 반영하려면 재분석(POST /{resumeId}/reanalyze)을 호출해야 한다.
                    검증은 형태만 엄격하다: 구조 오류·배열 내 null은 400, 필드 누락·빈 배열은 허용.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공 — 저장된 결과 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "구조 오류·배열 내 null·100KB 초과(C002)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인의 이력서(R009)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이력서 없음(R008)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "분석 진행 중(R010)·분석 실패 상태(R011 — 재분석 필요)"),
    })
    ResponseEntity<ApiResponse<ResumeParsedResponse>> updateParsed(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "이력서 ID", required = true) Long resumeId,
            ResumeParsedUpdateRequest request
    );

    @Operation(
            summary = "재분석 요청",
            description = """
                    이력서를 다시 분석하도록 요청한다. 모드는 서버가 상태로 결정한다 —
                    EMBEDDED(수정 반영)는 저장된 구조화 결과부터 재색인(REINDEX),
                    FAILED(실패 복구)는 S3 원본부터 전체 파이프라인(FULL).
                    진행 상태는 SSE(GET /sse/v1/resumes)로 전달된다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재분석 요청 접수 — 재시작된 상태 반환(REINDEX→EMBEDDING, FULL→UPLOADED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "타인의 이력서(R009)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이력서 없음(R008)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "분석 진행 중(R010)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "분석 요청 발행 실패(R007)"),
    })
    ResponseEntity<ApiResponse<ResumeReanalyzeResponse>> reanalyze(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "이력서 ID", required = true) Long resumeId
    );
}
