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

    // 이력서 (R) — docs/requirements/resume/resume.md §1
    FILE_REQUIRED(HttpStatus.BAD_REQUEST, "R001", "업로드할 파일이 필요합니다."),
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "R002", "PDF 파일만 업로드할 수 있습니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "R003", "파일 크기는 10MB를 초과할 수 없습니다."),
    INVALID_PDF(HttpStatus.BAD_REQUEST, "R004", "손상되었거나 읽을 수 없는 PDF 파일입니다."),
    PAGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "R005", "PDF는 최대 10페이지까지 업로드할 수 있습니다."),
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "R006", "파일 저장에 실패했습니다."),
    RESUME_ANALYSIS_REQUEST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "R007", "이력서 분석 요청에 실패했습니다."),

    // 인증 (A)
    INVALID_CODE(HttpStatus.BAD_REQUEST, "A001", "카카오 인가 코드가 누락되었거나 형식이 올바르지 않습니다."),
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "A002", "카카오 인증에 실패했습니다. 다시 로그인해 주세요."),
    KAKAO_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "A003", "카카오 서버와의 통신에 실패했습니다."),
    MISSING_REQUIRED_CONSENT(HttpStatus.BAD_REQUEST, "A004", "필수 동의 항목에 모두 동의해야 가입할 수 있습니다."),
    INVALID_SIGNUP_TOKEN(HttpStatus.UNAUTHORIZED, "A005", "가입 토큰이 유효하지 않습니다. 다시 로그인해 주세요."),
    ALREADY_REGISTERED(HttpStatus.CONFLICT, "A006", "이미 가입된 계정입니다."),
    RT_NOT_FOUND(HttpStatus.UNAUTHORIZED, "A007", "유효하지 않은 토큰입니다. 다시 로그인해 주세요."),
    RT_EXPIRED(HttpStatus.UNAUTHORIZED, "A008", "토큰이 만료되었습니다. 다시 로그인해 주세요."),
    RT_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "A009", "다른 기기에서 토큰 재사용이 감지되었습니다. 다시 로그인해 주세요."),

    // 사용자 (U)
    INVALID_NAME(HttpStatus.BAD_REQUEST, "U001", "이름은 앞뒤 공백을 제외하고 1~100자여야 합니다."),
    PURGE_IN_PROGRESS(HttpStatus.CONFLICT, "U002", "탈퇴 처리 중인 계정입니다. 잠시 후 다시 시도해 주세요."),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}
