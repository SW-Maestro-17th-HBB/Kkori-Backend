package com.aisw.kkori.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;

/**
 * 생성/수정 시각과 소프트 삭제를 관리하는 엔티티 공통 상위 타입.
 *
 * <p>{@code createdAt}/{@code updatedAt}은 JPA Auditing으로 자동 기록된다
 * (활성화는 {@code JpaConfig} 참조 — auditing 시각도 주입된 UTC {@code Clock}을 따른다).
 * {@code deletedAt}은 소프트 삭제 시각으로, null이면 살아있는 레코드다.
 * {@link #softDelete(Instant)}로 삭제 표시한다.
 *
 * <p>모든 시각은 UTC 절대 시점({@link Instant})으로 저장한다. 시각은 호출부가 주입된
 * {@code Clock}에서 얻어 전달하며, 엔티티 내부에서 시스템 시간을 직접 읽지 않는다
 * (시간 조건의 경계 테스트를 위해 — PRD 공통: 시각 처리).
 *
 * <p>조회 시 삭제 레코드를 자동 제외하려면 상속 엔티티에
 * {@code @SQLRestriction("deleted_at IS NULL")}을 붙인다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant deletedAt;

    /** 소프트 삭제 표시. 이미 삭제된 경우 시각을 갱신하지 않는다. */
    public void softDelete(Instant now) {
        Objects.requireNonNull(now, "now");
        if (this.deletedAt == null) {
            this.deletedAt = now;
        }
    }

    /** 삭제 표시를 되돌린다. */
    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
