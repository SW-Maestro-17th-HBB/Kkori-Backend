package com.aisw.kkori.global.oauth;

/**
 * 카카오 인증 완료 후 확보한 사용자 신원.
 *
 * @param providerId 카카오 회원번호 (필수)
 * @param email      이메일 제공 미동의 시 null
 * @param nickname   프로필 제공 미동의 시 null
 */
public record KakaoUserInfo(String providerId, String email, String nickname) {
}
