package com.aisw.kkori.global.exception;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.global.response.ErrorResponse;
import com.aisw.kkori.global.response.ErrorResponse.FieldError;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

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

    /** 파싱 불가능한(잘못된 형식의) 요청 바디. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE)));
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
