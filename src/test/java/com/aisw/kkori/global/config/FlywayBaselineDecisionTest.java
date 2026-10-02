package com.aisw.kkori.global.config;

import com.aisw.kkori.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FlywayConfig}의 baseline 판정 — 앱 컨텍스트와 분리된 스크래치 DB에 Flyway API로 직접 적용해
 * history 테이블과 실제 객체를 단언한다. 세 경로: 빈 DB, 타 레포 테이블만 먼저 생긴 새 DB(CodeRabbit 지적 —
 * 고정 baseline 1이면 V1이 건너뛰어져 V2가 실패했다), Hibernate가 만든 기존 스키마(prod·기존 로컬 볼륨).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayBaselineDecisionTest {

    @Autowired PostgreSQLContainer<?> postgres;

    private String dbName;
    private DataSource scratch;

    @BeforeEach
    void createScratchDatabase() {
        dbName = "flyway_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        new JdbcTemplate(adminDataSource()).execute("CREATE DATABASE " + dbName);
        scratch = new DriverManagerDataSource(
                postgres.getJdbcUrl().replaceFirst("/" + postgres.getDatabaseName() + "(\\?|$)", "/" + dbName + "$1"),
                postgres.getUsername(), postgres.getPassword());
    }

    @AfterEach
    void dropScratchDatabase() {
        new JdbcTemplate(adminDataSource()).execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
    }

    @Test
    @DisplayName("빈 DB는 baseline 없이 V1·V2가 실행된다")
    void emptyDatabaseRunsAllMigrations() {
        assertThat(FlywayConfig.resolveBaselineVersion(scratch)).isEqualTo("0");

        migrateLikeApp();

        assertThat(history()).containsExactly("1:SQL", "2:SQL");
        assertSpringSchemaPresent();
    }

    @Test
    @DisplayName("타 레포 소유 테이블만 먼저 생긴 새 DB도 baseline 0으로 기록하고 V1부터 실행한다")
    void foreignTablesOnlyStillRunsV1() {
        // 배포 순서상 에이전트 마이그레이션(interview_transcript)이 Spring 첫 기동보다 먼저다
        new JdbcTemplate(scratch).execute("""
                CREATE TABLE interview_transcript (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    session_id BIGINT NOT NULL UNIQUE,
                    content JSONB NOT NULL,
                    deleted_at TIMESTAMPTZ
                )""");
        assertThat(FlywayConfig.resolveBaselineVersion(scratch)).isEqualTo("0");

        migrateLikeApp();

        assertThat(history()).containsExactly("0:BASELINE", "1:SQL", "2:SQL");
        assertSpringSchemaPresent();
    }

    @Test
    @DisplayName("Hibernate가 만든 기존 스키마는 baseline 1로 기록하고 V2만 실행한다 (V1 재실행 없음)")
    void hibernateMadeSchemaSkipsV1() {
        // Flyway 도입 전 스키마 재현: V1만 적용한 뒤 history를 지운다(= ddl-auto가 만든 테이블만 있는 상태)
        Flyway.configure().dataSource(scratch).locations("classpath:db/migration").target("1").load().migrate();
        new JdbcTemplate(scratch).execute("DROP TABLE flyway_schema_history");
        assertThat(FlywayConfig.resolveBaselineVersion(scratch)).isEqualTo("1");

        migrateLikeApp();

        assertThat(history()).containsExactly("1:BASELINE", "2:SQL");
        assertSpringSchemaPresent();
    }

    /** 앱과 같은 설정(baseline-on-migrate + FlywayConfig 판정)으로 스크래치 DB에 마이그레이션한다. */
    private void migrateLikeApp() {
        Flyway.configure()
                .dataSource(scratch)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(FlywayConfig.resolveBaselineVersion(scratch))
                .load()
                .migrate();
    }

    /** history 행을 "version:type" 순서열로 — baseline 여부와 실행된 버전을 한눈에 비교한다. */
    private List<String> history() {
        return new JdbcTemplate(scratch).queryForList(
                "SELECT version || ':' || type FROM flyway_schema_history ORDER BY installed_rank", String.class);
    }

    private void assertSpringSchemaPresent() {
        JdbcTemplate jdbc = new JdbcTemplate(scratch);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.users') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.deletion_log') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_deletion_log_active_user'", Integer.class))
                .isEqualTo(1);
    }

    private DataSource adminDataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
