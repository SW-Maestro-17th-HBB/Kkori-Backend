package com.aisw.kkori.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 관련 설정.
 *
 * <p>{@code @EnableJpaAuditing}으로 {@code BaseEntity}의 생성/수정 시각 자동 기록을 활성화한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
