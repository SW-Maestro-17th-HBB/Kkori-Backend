package com.aisw.kkori.global.exception;

import lombok.Getter;

/**
 * 비즈니스 규칙 위반을 표현하는 예외.
 *
 * <p>도메인 서비스에서 {@link ErrorCode}와 함께 던지면
 * {@link GlobalExceptionHandler}가 공통 응답 포맷으로 변환한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 기본 메시지 대신 상황별 메시지를 노출하고 싶을 때 사용. */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
