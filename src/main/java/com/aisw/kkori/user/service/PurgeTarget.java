package com.aisw.kkori.user.service;

import java.time.Instant;

/**
 * 파기 단계에 전달되는 선점 건 식별자 (PRD deletion.md 기능 2·3).
 *
 * @param deletionLogId 선점한 {@code deletion_log} id
 * @param userId        파기 대상 유저 내부 id — 모든 단계의 대상 식별 키
 * @param claimedAt     선점 시각 — 이 건에 대한 {@code deletion_log} 쓰기의 펜싱 토큰
 */
public record PurgeTarget(long deletionLogId, long userId, Instant claimedAt) {
}
