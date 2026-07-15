package com.aisw.kkori.user.dto;

import com.aisw.kkori.user.domain.User;

import java.time.Instant;

/**
 * 내 정보 조회·수정 응답. {@code email}·{@code name}은 카카오 제공 여부에 따라 null일 수 있다.
 * {@code provider_id}는 내부 식별 정보이므로 노출하지 않는다.
 */
public record UserInfoResponse(
        Long id,
        String email,
        String name,
        Instant createdAt
) {

    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
    }
}
