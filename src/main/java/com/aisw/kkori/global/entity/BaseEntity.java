package com.aisw.kkori.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 생성/수정 시각과 소프트 삭제를 관리하는 엔티티 공통 상위 타입.
 *
 * <p>{@code createdAt}/{@code updatedAt}은 JPA Auditing으로 자동 기록된다
 * (활성화는 {@code JpaConfig} 참조). {@code deletedAt}은 소프트 삭제 시각으로,
 * null이면 살아있는 레코드다. {@link #softDelete()}로 삭제 표시한다.
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
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime deletedAt;

    /** 소프트 삭제 표시. 이미 삭제된 경우 시각을 갱신하지 않는다. */
    public void softDelete() {
        if (this.deletedAt == null) {
            this.deletedAt = LocalDateTime.now();
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
