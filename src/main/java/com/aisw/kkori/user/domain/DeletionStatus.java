package com.aisw.kkori.user.domain;

/**
 * 탈퇴 요청의 파기 작업 진행 상태 (PRD {@code docs/requirements/user/account.md} §회원 탈퇴).
 *
 * <p>전이 규칙: {@code PENDING_PURGE → CANCELLED}(복구 — HBB1-245),
 * {@code PENDING_PURGE·FAILED → PURGING}(파기 선점) {@code → PURGED/FAILED}(영구 삭제 스토리).
 * {@code PURGED}·{@code CANCELLED}는 종결 상태로 재전이가 없다. 모든 전이는 조건부 UPDATE로
 * 수행해 복구와 파기가 상호 배타가 되게 한다.
 */
public enum DeletionStatus {
    /** 탈퇴됨, 파기 대기. */
    PENDING_PURGE,
    /** 파기 배치가 선점해 작업 중. */
    PURGING,
    /** 파기 완료 (종결). */
    PURGED,
    /** 파기 실패 — 재시도 대상. */
    FAILED,
    /** 유예 내 복구로 파기 취소 (종결). */
    CANCELLED
}
