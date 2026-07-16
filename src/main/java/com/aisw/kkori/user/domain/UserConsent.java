package com.aisw.kkori.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;


@Getter
@Entity
@Table(name = "user_consent",
        indexes = @Index(name = "ix_user_consent_user_id", columnList = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 32)
    private ConsentType consentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentAction action;

    /** 동의서 버전. 버전 관리 정책은 HBB1-12 범위로, 그 전까지 1로 고정한다. */
    @Column(nullable = false)
    private int version;

    /**
     * append 시각. JPA Auditing이 아닌 트랜잭션 시각을 명시로 기록한다 —
     * 탈퇴 트랜잭션의 WITHDRAWN은 {@code deletion_log.requested_at}과 동일 값이어야 하는데
     * auditing은 persist 시점에 별도 시각을 찍기 때문(PRD 공통: 시각 처리).
     */
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    private UserConsent(Long userId, ConsentType consentType, ConsentAction action, int version, Instant createdAt) {
        this.userId = userId;
        this.consentType = consentType;
        this.action = action;
        this.version = version;
        this.createdAt = createdAt;
    }

    /** 동의 이력 한 건을 기록한다 — agreed 여부에 따라 AGREED/WITHDRAWN 행이 append된다. */
    public static UserConsent create(Long userId, ConsentType consentType, boolean agreed, int version, Instant createdAt) {
        return new UserConsent(userId, consentType, agreed ? ConsentAction.AGREED : ConsentAction.WITHDRAWN, version, createdAt);
    }
}
