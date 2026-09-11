package com.aisw.kkori.session.repositoryservice;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 에이전트 소유 테이블 {@code interview_transcript}의 파기 마스킹 (PRD deletion.md 기능 3).
 *
 * <p>Spring이 이 테이블에 쓰는 유일한 경로 — 소유권 원칙("쓰기 권한 경계 = 소유권 경계")의 명시적 예외로
 * 크로스 레포 계약에 기록되어 있다. 행은 유지하고 {@code content}를 빈 배열로, {@code deleted_at}을 기록한다
 * (NOT NULL·발화 배열 형식 유지, 에이전트의 {@code ON CONFLICT DO NOTHING} flush 멱등성 보존).
 * {@code deleted_at IS NULL} 술어로 재실행이 멱등이다. 테이블이 없으면 실패한다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcTranscriptPurger {

    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /** 세션들의 대본을 마스킹하고 실제로 마스킹된 행 수를 반환한다. */
    public int maskBySessionIds(List<Long> sessionIds, Instant now) {
        if (sessionIds.isEmpty()) {
            return 0;
        }
        return namedJdbcTemplate.update("""
                UPDATE interview_transcript
                SET content = '[]'::jsonb, deleted_at = :now
                WHERE session_id IN (:ids) AND deleted_at IS NULL
                """, new MapSqlParameterSource("ids", sessionIds).addValue("now", Timestamp.from(now)));
    }
}
