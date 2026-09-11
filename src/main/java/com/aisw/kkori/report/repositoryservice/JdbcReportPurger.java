package com.aisw.kkori.report.repositoryservice;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 리포트 물리 삭제의 네이티브 접근 (PRD deletion.md 기능 3 — report.md "회원 탈퇴 파기").
 *
 * <p>네이티브인 이유: {@code reports}는 {@code @SQLRestriction}이라 soft delete된 리포트가 JPA로 보이지 않고,
 * {@code report_generation_jobs}는 Worker 소유 테이블이라 엔티티가 없다(소유권 원칙의 명시적 예외 —
 * PRD 공통: 크로스 레포 계약, {@link JdbcReportJobWriter}와 같은 최소 결합). 순서는
 * 피드백 → 점수 → Job → 리포트. 트랜잭션은 소유하지 않는다(호출자의 user 잠금 트랜잭션 안).
 */
@Repository
@RequiredArgsConstructor
public class JdbcReportPurger {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /** 유저의 모든 리포트 id(soft delete 포함). */
    public List<Long> findReportIdsByUserId(long userId) {
        return jdbcTemplate.queryForList("SELECT id FROM reports WHERE user_id = ? ORDER BY id", Long.class, userId);
    }

    /** 리포트 4테이블 물리 삭제 — 삭제된 리포트 행 수를 반환한다. 이미 지워진 id는 0행으로 멱등. */
    public int deleteByReportIds(List<Long> reportIds) {
        if (reportIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", reportIds);
        namedJdbcTemplate.update("DELETE FROM report_feedbacks WHERE report_id IN (:ids)", params);
        namedJdbcTemplate.update("DELETE FROM report_scores WHERE report_id IN (:ids)", params);
        namedJdbcTemplate.update("DELETE FROM report_generation_jobs WHERE report_id IN (:ids)", params);
        return namedJdbcTemplate.update("DELETE FROM reports WHERE id IN (:ids)", params);
    }
}
