package com.aisw.kkori.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그아웃 요청. 200은 미존재·타인 소유 RT에 대한 규칙이고,
 * body 자체가 비어 있는 malformed 요청은 400으로 거른다.
 */
public record LogoutRequest(@NotBlank String refreshToken) {
}
