package com.aisw.kkori.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 탈퇴 요청·파기 이력 (audit 근거로 영구 보존 — soft delete 대상이 아니므로 BaseEntity 미상속).
 *
 * <p>시각은 전부 명시 관리한다: 탈퇴 트랜잭션의 {@code requestedAt}은
 * {@code users.deleted_at}·동의 {@code WITHDRAWN.created_at}과 동일한 값이어야 하는데(PRD 공통: 시각 처리),
 * JPA Auditing은 persist 시점에 별도 시각을 찍어 이 동일성을 보장하지 못한다.
 *
 * <p>{@code updatedAt}은 마지막 상태 전이 시각으로, 생성 시 {@code requestedAt}과 같은 값으로 시작한다.
 * 상태 전이는 조건부 UPDATE(벌크 쿼리)로 수행되어 auditing이 적용되지 않으므로,
 * 전이 쿼리는 반드시 {@code updatedAt = :now}를 함께 갱신해야 한다.
 */
@Getter
@Entity
@Table(name = "deletion_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeletionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 탈퇴한 유저의 id. FK 없이 애플리케이션이 무결성을 관리한다(refresh_token과 동일 방침). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 탈퇴 시점의 카카오 회원번호 스냅샷 — 파기 배치의 unlink 호출 재료.
     * {@code users.provider_id}는 유예 만료 처리로 먼저 마스킹될 수 있어 여기 확보해 둔다.
     * 개인 식별정보이므로 복구(CANCELLED 전환)·파기 완료(unlink 후) 시 NULL 처리한다.
     */
    @Column(name = "provider_id", length = 64)
    private String providerId;

    /** 탈퇴 요청 시각(= 탈퇴 트랜잭션 시각). 유예 만료 판정의 기준점. */
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    /** 파기 완료 시각. NULL이면 미파기. 기록은 영구 삭제 스토리 범위. */
    @Column(name = "purged_at")
    private Instant purgedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeletionStatus status;

    /** 파기 대상·결과 구조화(jsonb). 구조 정의·기록은 영구 삭제 스토리 범위 — 그 전까지 항상 NULL. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "purge_detail")
    private String purgeDetail;

    /** 마지막 상태 전이 시각. stale PURGING 회수·FAILED 재시도 판정 재료(영구 삭제 스토리). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private DeletionLog(Long userId, String providerId, Instant requestedAt) {
        this.userId = userId;
        this.providerId = providerId;
        this.requestedAt = requestedAt;
        this.status = DeletionStatus.PENDING_PURGE;
        this.updatedAt = requestedAt;
    }

    /** 탈퇴 요청을 파기 대기로 등록한다. {@code now}는 탈퇴 트랜잭션 시각. */
    public static DeletionLog pending(Long userId, String providerId, Instant now) {
        return new DeletionLog(userId, providerId, now);
    }
}
