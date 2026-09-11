package com.aisw.kkori.resume.repositoryservice;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
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

    /** 개별 삭제 물리 삭제 후보 — 공유 키 판정에 필요한 소유자·해시를 함께 든다. */
    public record Candidate(long resumeId, long userId, String fileHash, String bucket, String key) {
    }

    /**
     * 개별 삭제(soft delete) 이력서의 물리 삭제 후보 (PRD deletion.md 기능 5) — soft delete 후 지연이 지났고,
     * 분석이 terminal(EMBEDDED·FAILED)이거나 보류 상한({@code ceilingCutoff})까지 지난 행. 상태 행 부재(정합
     * 깨짐)는 보류할 근거가 없으므로 포함한다.
     */
    public List<Candidate> findPhysicalDeleteCandidates(Instant delayCutoff, Instant ceilingCutoff) {
        return jdbcTemplate.query("""
                SELECT r.id, r.user_id, r.file_hash, r.original_file_bucket, r.original_file_key
                FROM resumes r
                LEFT JOIN resume_analysis_status s ON s.resume_id = r.id
                WHERE r.deleted_at IS NOT NULL
                  AND r.deleted_at <= ?
                  AND (s.parse_status IS NULL OR s.parse_status IN ('EMBEDDED', 'FAILED') OR r.deleted_at <= ?)
                ORDER BY r.deleted_at, r.id
                """,
                (rs, rowNum) -> new Candidate(rs.getLong("id"), rs.getLong("user_id"), rs.getString("file_hash"),
                        rs.getString("original_file_bucket"), rs.getString("original_file_key")),
                Timestamp.from(delayCutoff), Timestamp.from(ceilingCutoff));
    }

    /**
     * 같은 사용자·같은 해시의 soft delete 행들을 잠근다(FOR UPDATE, id 순) — 업로드가 물리 삭제 배치와
     * 직렬화하는 지점. users 행이 아니라 이 행을 잠그는 이유: 배치가 S3 왕복 동안 users 행을 쥐면 같은
     * 유저의 탈퇴 웹훅(2초 트랜잭션)이 밀리는데, 이 행은 웹훅·계정 경로가 건드리지 않는다.
     * 호출자의 트랜잭션 안에서만 의미가 있다(잠금 수명 = 트랜잭션).
     */
    public List<Long> lockSoftDeletedByUserIdAndFileHash(long userId, String fileHash) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM resumes WHERE user_id = ? AND file_hash = ? AND deleted_at IS NOT NULL "
                        + "ORDER BY id FOR UPDATE",
                Long.class, userId, fileHash);
    }

    /** 물리 삭제 후보 행 잠금 — 이미 사라졌으면(탈퇴 파기·타 인스턴스) false. 호출자의 트랜잭션 안에서. */
    public boolean lockSoftDeletedById(long resumeId) {
        return !jdbcTemplate.queryForList(
                "SELECT id FROM resumes WHERE id = ? AND deleted_at IS NOT NULL FOR UPDATE",
                Long.class, resumeId).isEmpty();
    }

    /**
     * 고아 청크 정리 — {@code resumes} 행이 없는 {@code resume_id}의 청크 중 생성 후 {@code createdBefore} 이전 것만
     * 삭제한다(PRD deletion.md 기능 5). 상한 삭제 뒤 Worker가 뒤늦게 남긴 청크(이력서 본문)는 행 기준 후보 조회로는
     * 다시 찾지 못하므로 여기서 거둔다. 생성 직후 청크를 제외하는 이유: 업로드가 행 커밋과 같은 트랜잭션에서 분석
     * 요청을 발행해, Worker가 행 커밋보다 먼저 청크를 쓰는 짧은 창이 이론상 있다(그 청크는 고아가 아니다).
     */
    public int deleteOrphanChunksCreatedBefore(Instant createdBefore) {
        return jdbcTemplate.update("""
                DELETE FROM resume_chunks c
                WHERE c.created_at <= ?
                  AND NOT EXISTS (SELECT 1 FROM resumes r WHERE r.id = c.resume_id)
                """, Timestamp.from(createdBefore));
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
