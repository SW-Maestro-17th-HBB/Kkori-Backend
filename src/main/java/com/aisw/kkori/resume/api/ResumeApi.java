package com.aisw.kkori.resume.api;

import com.aisw.kkori.global.response.ApiResponse;
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
}
