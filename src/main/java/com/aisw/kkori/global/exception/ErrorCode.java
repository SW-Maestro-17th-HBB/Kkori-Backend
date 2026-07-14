package com.aisw.kkori.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 애플리케이션 전역 에러 코드.
 *
 * <p>코드 규칙: 접두사 1글자(도메인) + 3자리 일련번호. 공통은 {@code C}.
 * 도메인 예외는 각 도메인 접두사(예: 이력서 {@code R}, 인증 {@code A})로 이 enum에 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통 (C)
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C001", "서버 내부 오류가 발생했습니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C002", "입력값이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "지원하지 않는 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C004", "요청한 리소스를 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C005", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "C006", "접근 권한이 없습니다."),

    // 이력서 (R) — docs/requirements/resume.md §1
    FILE_REQUIRED(HttpStatus.BAD_REQUEST, "R001", "업로드할 파일이 필요합니다."),
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "R002", "PDF 파일만 업로드할 수 있습니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "R003", "파일 크기는 10MB를 초과할 수 없습니다."),
    INVALID_PDF(HttpStatus.BAD_REQUEST, "R004", "손상되었거나 읽을 수 없는 PDF 파일입니다."),
    PAGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "R005", "PDF는 최대 10페이지까지 업로드할 수 있습니다."),
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "R006", "파일 저장에 실패했습니다."),
    RESUME_ANALYSIS_REQUEST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "R007", "이력서 분석 요청에 실패했습니다."),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}
