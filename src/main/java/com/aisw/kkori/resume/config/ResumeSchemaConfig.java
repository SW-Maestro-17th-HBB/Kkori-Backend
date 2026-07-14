package com.aisw.kkori.resume.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JPA 매핑으로 표현할 수 없는 스키마 요소 보강.
 *
 * <p>활성 이력서(soft delete 제외)의 file_hash 유일성은 부분 유니크 인덱스로 강제한다 —
 * 전체 컬럼 유니크로 걸면 soft delete 후 같은 파일 재업로드가 막히므로 {@code WHERE deleted_at IS NULL} 조건 필수.
 * Hibernate는 부분 인덱스를 선언할 수 없어 기동 시 멱등 DDL로 생성한다(ddl-auto와 무관하게 전 환경 동일 경로).
 *
 * <p>TODO: 인증 도입 시 (user_id, file_hash) 복합으로 교체 / 마이그레이션 도구(Flyway) 도입 시 그쪽으로 이관.
 */
@Configuration
public class ResumeSchemaConfig {

    @Bean
    public ApplicationRunner resumeFileHashIndexInitializer(JdbcTemplate jdbcTemplate) {
        return args -> jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_resumes_active_file_hash
                ON resumes (file_hash)
                WHERE deleted_at IS NULL
                """);
    }
}
