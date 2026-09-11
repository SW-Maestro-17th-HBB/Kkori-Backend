package com.aisw.kkori.global.oauth.dto;

/** 카카오 REST API 공통 오류 응답 — {@code {"msg": "...", "code": -101}}. 필요한 필드만 읽는다. */
public record KakaoErrorResponse(Integer code, String msg) {
}
