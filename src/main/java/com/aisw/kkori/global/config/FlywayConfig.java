package com.aisw.kkori.global.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Flyway baseline 버전을 기동 시 DB 상태로 결정한다.
 *
 * <p>Flyway는 history 테이블이 없는 비어 있지 않은 스키마를 만나면({@code baseline-on-migrate})
 * baseline 버전을 기록하고 <b>그보다 큰</b> 버전만 적용한다. 고정값 1을 쓰면 Hibernate ddl-auto가
 * 만든 기존 스키마(prod·기존 로컬 볼륨)에서는 의도대로 V1을 건너뛰지만, 타 레포 소유 테이블
 * ({@code interview_transcript} 등 — 배포 순서상 에이전트 마이그레이션이 Spring보다 먼저다)만 먼저
 * 생긴 새 DB에서도 "비어 있지 않음"으로 판정돼 V1이 건너뛰어지고 V2가 {@code deletion_log} 부재로
 * 실패한다. 게다가 baseline 행이 남아 이후 기동도 V1을 영구히 건너뛴다.
 *
 * <p>그래서 baseline 버전을 Spring 소유 스키마의 존재 여부로 정한다 — V1의 첫 테이블인 {@code users}가
 * 있으면 1(기존 스키마, V1 skip), 없으면 0(빈 DB든 외부 테이블만 있든 V1부터 실행). history가 이미
 * 있는 DB에는 baseline 자체가 적용되지 않으므로 영향이 없다. 판정 경로는
 * {@code FlywayBaselineDecisionTest}가 고정한다.
 */
@Configuration
public class FlywayConfig {

    static final String SPRING_SCHEMA_SENTINEL_TABLE = "public.users";

    @Bean
    FlywayConfigurationCustomizer baselineVersionBySchemaOrigin(DataSource dataSource) {
        return configuration -> configuration.baselineVersion(resolveBaselineVersion(dataSource));
    }

    /** Hibernate가 만든 기존 스키마면 "1", 아니면 "0". */
    static String resolveBaselineVersion(DataSource dataSource) {
        Boolean exists = new JdbcTemplate(dataSource).queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, SPRING_SCHEMA_SENTINEL_TABLE);
        return Boolean.TRUE.equals(exists) ? "1" : "0";
    }
}
