package com.aisw.kkori.session.repositoryservice;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 에이전트 소유 테이블 {@code interview_transcript}의 읽기 전용 조회.
 *
 * <p>행 존재는 정상 종료의 단독 증거다 — flush(INSERT)는 에이전트의 정상 종료 시퀀스에서만
 * 일어난다(크로스 레포 계약: Kkori-AI interview-end.md §3, PRD interview-session-completion.md).
 * 테이블 DDL·마이그레이션·쓰기는 에이전트 소유이므로 JPA 엔티티를 만들지 않고 EXISTS만
 * 조회한다(스키마 계약: {@code id, session_id UNIQUE, content jsonb, deleted_at}). dev/prod에서
 * 테이블은 에이전트 배포가 선행 조건이고, 테스트는 계약 픽스처 DDL로 생성한다.
 */
@Repository
@RequiredArgsConstructor
public class InterviewTranscriptReader {

    private final JdbcTemplate jdbcTemplate;

    /** 세션의 transcript 행 존재 여부 — terminal 확정 원칙(ENDED ↔ 행 존재)의 판별 입력. */
    public boolean exists(long sessionId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from interview_transcript where session_id = ?)",
                Boolean.class, sessionId);
        return Boolean.TRUE.equals(exists);
    }
}
