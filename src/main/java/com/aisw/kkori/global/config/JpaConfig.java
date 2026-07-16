package com.aisw.kkori.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * JPA 관련 설정.
 *
 * <p>{@code @EnableJpaAuditing}으로 {@code BaseEntity}의 생성/수정 시각 자동 기록을 활성화한다.
 * auditing 시각은 시스템 시간이 아닌 주입된 {@code Clock}을 따른다 —
 * 테스트에서 Clock을 고정하면 createdAt/updatedAt까지 제어된다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        // 마이크로초 절삭 — PostgreSQL timestamptz(6)는 나노초 입력을 마이크로초로 반올림 저장하므로
        // (Linux JDK는 나노초 시계), 절삭 없이는 영속화 전 메모리 값과 DB 재조회 값이 어긋난다
        return () -> Optional.of(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    }
}
