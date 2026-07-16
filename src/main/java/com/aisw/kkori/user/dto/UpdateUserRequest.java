package com.aisw.kkori.user.dto;

/**
 * 내 정보 수정 요청. 수정 가능한 필드는 {@code name} 하나이며, 그 외 필드는 바인딩 대상이
 * 아니므로 무시된다(전방 호환 — PRD 기능 2). 검증은 도메인 에러 코드(U001)로 응답하기 위해
 * bean validation이 아닌 서비스 계층에서 수행한다.
 */
public record UpdateUserRequest(String name) {
}
