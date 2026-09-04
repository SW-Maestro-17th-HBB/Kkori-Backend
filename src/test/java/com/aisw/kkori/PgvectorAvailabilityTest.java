package com.aisw.kkori;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 이미지의 pgvector 지원 검증. 임베딩 벡터 스키마(resume_chunks.embedding vector(1024),
 * PRD resume.md §1)의 테이블은 Python Worker가 만들지만, DB 인프라(이미지)는 이 리포가 제공한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PgvectorAvailabilityTest {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("pgvector 확장을 설치하고 vector 타입을 실제로 사용할 수 있다")
    void pgvectorExtension_isInstallableAndUsable() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
                .isEqualTo(1);

        // 타입이 실제로 동작하는지 — Worker 계약 차원(1024)의 벡터 컬럼을 만들 수 있어야 한다
        jdbcTemplate.execute("CREATE TABLE pgvector_smoke (embedding vector(1024))");
        jdbcTemplate.execute("DROP TABLE pgvector_smoke");
    }
}
