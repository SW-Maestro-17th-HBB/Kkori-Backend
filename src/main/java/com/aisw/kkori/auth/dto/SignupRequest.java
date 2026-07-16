package com.aisw.kkori.auth.dto;

import com.aisw.kkori.user.domain.ConsentType;

import java.util.List;

/**
 * 회원가입 완료 요청.
 *
 * <p>{@code signupToken} 누락·위변조는 서비스에서 A005로, 동의 항목의 형태·버전·필수 검증은
 * 서비스에서 도메인 코드(C002 → U005 → A004 순)로 처리하므로 bean validation을 걸지 않는다.
 * 알 수 없는 동의 {@code type} 문자열은 역직렬화 단계에서 400(C002)이 된다.
 */
public record SignupRequest(String signupToken, List<ConsentItem> consents) {

    /**
     * {@code type}·{@code agreed}는 필수(누락 시 400 — 형태 규칙, oauth.md).
     * {@code version}은 사용자가 확인한 동의서 버전으로 {@code agreed: true}일 때 필수이며
     * 서버 현재 버전과 대조된다(불일치 시 409 U005 — HBB1-12 버전 확인 계약).
     */
    public record ConsentItem(ConsentType type, Boolean agreed, Integer version) {
    }
}
