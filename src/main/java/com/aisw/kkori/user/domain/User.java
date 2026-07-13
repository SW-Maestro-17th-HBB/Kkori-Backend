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

    public static User create(String providerId, String email, String name) {
        return new User(providerId, email, name);
    }
}
