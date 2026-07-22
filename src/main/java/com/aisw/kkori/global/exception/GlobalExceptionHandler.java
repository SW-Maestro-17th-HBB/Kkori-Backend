package com.aisw.kkori.global.exception;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.global.response.ErrorResponse;
import com.aisw.kkori.global.response.ErrorResponse.FieldError;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 전역 예외 처리기.
 *
 * <p>모든 예외를 {@link ApiResponse} 실패 포맷으로 변환한다.
 * 새 예외 타입이 필요하면 여기에 핸들러를 추가한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 비즈니스 규칙 위반. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("BusinessException [{}]: {}", errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(errorCode, e.getMessage())));
    }

    /** {@code @Valid} 바디 검증 실패. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(err -> FieldError.of(err.getField(), err.getDefaultMessage()))
                .toList();
        log.warn("Validation failed: {}", fieldErrors);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, fieldErrors)));
    }

    /** {@code @Validated} 경로/쿼리 파라미터 검증 실패. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        List<FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(v -> FieldError.of(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        log.warn("Constraint violation: {}", fieldErrors);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, fieldErrors)));
    }

    /**
     * 파싱 불가능한(잘못된 형식의) 요청 바디. 특정 필드의 값 문제(미정의 enum 값 등,
     * Jackson {@link InvalidFormatException})는 fieldErrors로 어떤 필드가 문제인지 알려주고,
     * 구조 자체가 깨진 JSON은 fieldErrors 없이 응답한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        List<FieldError> fieldErrors = invalidFormatFieldErrors(e);
        ErrorResponse body = fieldErrors.isEmpty()
                ? ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE)
                : ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, fieldErrors);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ApiResponse.error(body));
    }

    private List<FieldError> invalidFormatFieldErrors(HttpMessageNotReadableException e) {
        if (!(e.getCause() instanceof InvalidFormatException cause)) {
            return List.of();
        }
        String field = cause.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        if (field.isBlank()) {
            return List.of();
        }
        return List.of(FieldError.of(field, "허용되지 않는 값입니다"));
    }

    /** 멀티파트 업로드 한도 초과 — 컨테이너(Tomcat) 레벨에서 발생해도 동일 엔벨로프로 변환한다. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("Upload size exceeded: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.FILE_TOO_LARGE)));
    }

    /** 지원하지 않는 HTTP 메서드. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not supported: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED)));
    }

    /** 그 외 처리되지 않은 모든 예외 (500). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR)));
    }
}
