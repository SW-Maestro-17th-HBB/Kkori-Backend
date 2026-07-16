package com.aisw.kkori.user.domain;

import com.aisw.kkori.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users", uniqueConstraints =
        @UniqueConstraint(name = "ux_users_provider_id", columnNames = "provider_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 카카오가 이메일 제공에 미동의한 유저는 null. */
    @Column
    private String email;

    /** 표시 이름. 가입 시 카카오 프로필 닉네임에서 가져오며 없으면 null. */
    @Column(length = 100)
    private String name;

    /** 카카오 회원번호. 멀티 프로바이더 확장을 대비해 문자열로 저장한다. */
    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    private User(String providerId, String email, String name) {
        this.providerId = providerId;
        this.email = email;
        this.name = name;
    }

    /** 가입 시 계정 생성. email·name은 카카오가 제공하지 않으면 null일 수 있다. */
    public static User create(String providerId, String email, String name) {
        return new User(providerId, email, name);
    }

    /** 표시 이름 변경. 검증(공백 제거 후 1~100 코드 포인트)은 서비스 계층이 담당한다. */
    public void updateName(String name) {
        this.name = name;
    }

    /**
     * 유예 초과 계정의 식별정보 선행 파기 (PRD 기능 4) — email·name은 NULL,
     * provider_id는 {@code PURGED_{id}} 마스킹. NULL이 아닌 마스킹인 이유:
     * NOT NULL 제약과 "모든 유저는 provider_id를 가진다" 불변식 유지.
     * 원본 복원 불가(해시 금지 — 카카오 회원번호는 숫자라 전수 대입 역산 가능)·
     * id 기반 유일·재실행 멱등.
     */
    public void purgeIdentifiers() {
        this.email = null;
        this.name = null;
        this.providerId = "PURGED_" + this.id;
    }
}
