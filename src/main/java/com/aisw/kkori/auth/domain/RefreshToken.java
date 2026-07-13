package com.aisw.kkori.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Refresh Token 저장 레코드.
 *
 * <p>토큰 평문은 저장하지 않고 SHA-256 해시({@code tokenHash})만 저장한다(유출 대비).
 * Grace Period에서 "원래 발급했던 RT"를 되돌려주기 위해 토큰 재생성 재료
 * ({@code userId}·{@code jti}·{@code createdAt}=iat·{@code expiredAt}=exp)를 함께 보관한다 —
 * HMAC 서명은 결정적이라 같은 재료로 동일한 토큰 문자열을 다시 만들 수 있다.
 *
 * <p>폐기 표현은 {@code revokedAt} 단독이다(PRD 제약 — boolean 병행 금지).
 * BaseEntity의 {@code updated_at}·{@code deleted_at}이 이 규칙과 충돌하므로 상속하지 않는다.
 */
@Getter
@Entity
@Table(name = "refresh_token",
        uniqueConstraints = @UniqueConstraint(name = "ux_refresh_token_token_hash", columnNames = "token_hash"),
        indexes = @Index(name = "ix_refresh_token_user_id", columnList = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** RT 평문의 SHA-256 hex. 재발급·로그아웃 시 이 값으로 조회한다. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** 토큰 JWT의 jti claim. 재생성 재료이자 동일 유저·동일 초 발급 토큰의 유니크 보장. */
    @Column(nullable = false, length = 36)
    private String jti;

    /** 토큰 JWT의 exp claim. 재생성 시 그대로 사용한다(TTL 설정 변경에도 안전). */
    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    /** 폐기 시각. NULL이면 유효. 값이 있으면 Grace Period 판정의 기준 시각이 된다. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** RTR 회전 시 발급한 다음 RT의 token_hash. 로그아웃·탈퇴 폐기는 NULL로 남는다. */
    @Column(name = "replaced_by", length = 64)
    private String replacedByTokenHash;

    /**
     * 토큰 JWT의 iat claim 값(초 단위 절삭)을 수동 세팅한다.
     * Grace Period의 토큰 재생성 재료이므로 JPA Auditing({@code @CreatedDate})으로 바꾸지 말 것 —
     * auditing은 persist 시각을 찍어 토큰 생성 시각과 어긋날 수 있다.
     */
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    private RefreshToken(Long userId, String tokenHash, String jti, Instant issuedAt, Instant expiredAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.jti = jti;
        this.createdAt = issuedAt;
        this.expiredAt = expiredAt;
    }

    public static RefreshToken issue(Long userId, String tokenHash, String jti, Instant issuedAt, Instant expiredAt) {
        return new RefreshToken(userId, tokenHash, jti, issuedAt, expiredAt);
    }

    /** RTR 회전 — 이 토큰을 폐기하고 다음 토큰의 해시를 기록한다. */
    public void rotateTo(String nextTokenHash) {
        this.revokedAt = Instant.now();
        this.replacedByTokenHash = nextTokenHash;
    }

    /** 로그아웃·탈퇴 등 회전 없는 폐기. replaced_by는 NULL로 남는다. */
    public void revoke() {
        if (this.revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !this.expiredAt.isAfter(now);
    }
}
