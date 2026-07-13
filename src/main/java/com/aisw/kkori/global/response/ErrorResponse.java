package com.aisw.kkori.global.response;

import com.aisw.kkori.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 실패 응답의 에러 본문.
 *
 * <p>{@code code}/{@code message}는 {@link ErrorCode}에서 가져오며,
 * 입력값 검증 실패 시 {@code fieldErrors}에 필드별 사유가 채워진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors
) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.getCode(), message, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), fieldErrors);
    }

    public record FieldError(String field, String reason) {
        public static FieldError of(String field, String reason) {
            return new FieldError(field, reason);
        }
    }
}
