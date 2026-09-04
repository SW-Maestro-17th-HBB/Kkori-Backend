package com.aisw.kkori.user.service;

/**
 * 복구 시도의 결과 (PRD account.md 기능 4).
 *
 * <p>{@link Expired}는 제출 시점에 유예가 만료되어 식별정보 선행 파기까지 수행된 상태를
 * 뜻한다 — 호출부({@code AuthService.signup})가 같은 트랜잭션에서 신규 가입 경로로 합류한다.
 */
public sealed interface RestoreResult {

    record Restored(Long userId) implements RestoreResult {
    }

    enum Expired implements RestoreResult {
        INSTANCE
    }
}
