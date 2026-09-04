package com.aisw.kkori.auth.dto;

/**
 * 카카오 로그인 요청. redirect_uri는 서버 설정값을 쓰므로 code만 받는다.
 *
 */
public record KakaoLoginRequest(String code) {
}
