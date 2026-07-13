package com.aisw.kkori.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

/**
 * 모든 API 응답의 공통 래퍼.
 *
 * <p>성공: {@code { "success": true, "data": {...} }} — 응답 데이터가 없어도
 * {@code { "success": true, "data": null }}처럼 {@code data} 키는 항상 포함된다.
 * 실패: {@code { "success": false, "data": null, "error": {...} }}.
 * {@code error}는 실패일 때만 포함된다(null이면 생략).
 */
@Getter
public class ApiResponse<T> {

    private final boolean success;
    private final T data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final ErrorResponse error;

    private ApiResponse(boolean success, T data, ErrorResponse error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> error(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}
