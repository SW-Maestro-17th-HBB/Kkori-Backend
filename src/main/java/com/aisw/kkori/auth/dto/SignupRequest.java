package com.aisw.kkori.auth.dto;

import com.aisw.kkori.user.domain.ConsentType;

import java.util.List;

/**
 * 회원가입 완료 요청.
 *
 * <p>{@code signupToken} 누락·위변조는 서비스에서 A005로, 필수 동의 누락은 A004로 처리하므로
 * bean validation을 걸지 않는다. 알 수 없는 동의 {@code type} 문자열은 400(C002)이 된다.
 */
public record SignupRequest(String signupToken, List<ConsentItem> consents) {

    public record ConsentItem(ConsentType type, boolean agreed) {
    }
}
