package com.aisw.kkori.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;


@Getter
@Entity
@Table(name = "user_consent",
        indexes = @Index(name = "ix_user_consent_user_id", columnList = "user_id"))
@EntityListeners(AuditingEntityListener.class)
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

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    private UserConsent(Long userId, ConsentType consentType, ConsentAction action, int version) {
        this.userId = userId;
        this.consentType = consentType;
        this.action = action;
        this.version = version;
    }

    public static UserConsent record(Long userId, ConsentType consentType, boolean agreed, int version) {
        return new UserConsent(userId, consentType, agreed ? ConsentAction.AGREED : ConsentAction.WITHDRAWN, version);
    }
}
