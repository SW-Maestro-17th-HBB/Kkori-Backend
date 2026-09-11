package com.aisw.kkori.resume.repositoryservice;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 이력서 물리 삭제의 네이티브 접근 (PRD deletion.md 기능 3·5).
 *
 * <p>네이티브인 이유 둘: (1) {@code resumes}는 {@code @SQLRestriction("deleted_at IS NULL")}이라 JPA로는
 * soft delete된 이력서가 보이지 않는데 파기 대상은 그 행을 포함한다. (2) {@code resume_chunks}는 Worker
 * 소유 테이블이라 JPA 엔티티가 없다 — 파기 시 Spring이 직접 DELETE하는 것은 소유권 원칙의 명시적
 * 예외다(PRD 공통: 크로스 레포 계약). 테이블이 없으면 실패한다(배포 순서 전제를 조용히 넘기지 않음).
 *
 * <p>트랜잭션은 소유하지 않는다 — 호출자가 user 행 잠금 트랜잭션 안에서 부른다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcResumePurger {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /** S3 원본 참조 — 파기 배치가 DB 포인터 삭제 전에 객체를 지우는 재료. */
    public record ObjectRef(long resumeId, String bucket, String key) {
    }

    /** 유저의 모든 이력서(soft delete 포함)의 S3 참조. */
    public List<ObjectRef> findObjectRefsByUserId(long userId) {
        return jdbcTemplate.query(
                "SELECT id, original_file_bucket, original_file_key FROM resumes WHERE user_id = ? ORDER BY id",
                (rs, rowNum) -> new ObjectRef(rs.getLong("id"), rs.getString("original_file_bucket"),
                        rs.getString("original_file_key")),
                userId);
    }

    /** 청크(Worker 소유) → 분석 상태 → 이력서 행 순 물리 삭제. 이미 지워진 id는 0행으로 멱등. */
    public PurgeCounts deleteByResumeIds(List<Long> resumeIds) {
        if (resumeIds.isEmpty()) {
            return new PurgeCounts(0, 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", resumeIds);
        int chunks = namedJdbcTemplate.update("DELETE FROM resume_chunks WHERE resume_id IN (:ids)", params);
        namedJdbcTemplate.update("DELETE FROM resume_analysis_status WHERE resume_id IN (:ids)", params);
        int rows = namedJdbcTemplate.update("DELETE FROM resumes WHERE id IN (:ids)", params);
        return new PurgeCounts(rows, chunks);
    }

    public record PurgeCounts(int rows, int chunks) {
    }
}
