package com.aisw.kkori.user;

import com.aisw.kkori.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2 마이그레이션의 부분 유니크 인덱스 {@code ux_deletion_log_active_user} 검증 (account.md deletion_log 인덱스).
 *
 * <p>{@code ddl-auto: validate}는 인덱스를 검사하지 않으므로, 인덱스의 존재와 술어(활성 상태만)는 이 테스트가 지킨다.
 * 앱 경로는 user 행 잠금으로 이미 직렬화되어 이 인덱스가 발동할 일이 없으므로 JDBC로 직접 행을 넣어 검증한다.
 * {@code user_id}는 FK가 없어 users 행 없이 임의 id를 쓴다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeletionLogActiveUniqueIndexTest {

    private static final long USER_ID = 9_100_001L;
    private static final String INSERT = """
            INSERT INTO deletion_log (user_id, provider_id, requested_at, status, updated_at)
            VALUES (?, ?, now(), ?, now())
            """;

    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        // 같은 컨텍스트(DB)를 공유하는 다른 테스트의 배치 스캔에 잡히지 않게 정리한다
        jdbcTemplate.update("DELETE FROM deletion_log WHERE user_id = ?", USER_ID);
    }

    @ParameterizedTest(name = "활성 행이 있는 유저에 {0} 행 추가 → 거부")
    @ValueSource(strings = {"PENDING_PURGE", "PURGING", "FAILED"})
    @DisplayName("유저당 활성(PENDING_PURGE·PURGING·FAILED) 삭제 요청은 1건만 존재할 수 있다")
    void secondActiveRowForSameUserIsRejected(String secondStatus) {
        jdbcTemplate.update(INSERT, USER_ID, "kakao-ux-1", "PENDING_PURGE");

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT, USER_ID, "kakao-ux-1", secondStatus))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ux_deletion_log_active_user");
    }

    @Test
    @DisplayName("종결(PURGED·CANCELLED) 이력은 같은 유저에 여러 건 남을 수 있고 그 뒤의 새 활성 요청도 허용된다 (부분 인덱스 술어)")
    void terminalRowsDoNotCountAsActive() {
        jdbcTemplate.update(INSERT, USER_ID, null, "CANCELLED");
        jdbcTemplate.update(INSERT, USER_ID, null, "PURGED");
        jdbcTemplate.update(INSERT, USER_ID, null, "PENDING_PURGE");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM deletion_log WHERE user_id = ?", Integer.class, USER_ID)).isEqualTo(3);
    }
}
