package com.aisw.kkori.session;

import org.junit.jupiter.api.BeforeEach;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 세션 종료 스토리(interview-session-completion.md) 테스트 공통 베이스.
 *
 * <p>{@code interview_transcript}는 에이전트 소유 테이블이라(DDL·마이그레이션 포함 — Kkori-AI
 * interview-end.md §4) 로컬·테스트 DB에 존재하지 않는다 — 계약 픽스처 DDL로 생성한다.
 * 스키마 자구는 계약 인용이다: {@code id, session_id UNIQUE, content jsonb, deleted_at}.
 *
 * <p>종료 표식은 실제 Redis(Testcontainers)에 계약 키·값 형식으로 시딩한다 — 판별이 읽는
 * 경로(키 조립·존재 판정·파싱)를 실물로 검증한다.
 */
abstract class SessionCompletionTestSupport extends InterviewSessionIntegrationTestSupport {

    @BeforeEach
    void prepareTranscriptFixture() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interview_transcript (
                    id bigserial PRIMARY KEY,
                    session_id bigint NOT NULL UNIQUE,
                    content jsonb NOT NULL,
                    deleted_at timestamptz
                )""");
        jdbcTemplate.update("DELETE FROM interview_transcript");
    }

    /** 정상 종료 증거 시딩 — flush는 정상 종료 시퀀스에서만 일어난다(행 존재 = 단독 증거). */
    void seedTranscript(long sessionId) {
        jdbcTemplate.update(
                "INSERT INTO interview_transcript (session_id, content) VALUES (?, '[]'::jsonb)", sessionId);
    }

    /** 종료 표식 시딩 — 계약 키·값 형식 (interview:{sessionId}:termination). */
    void seedMarker(long sessionId, String cause) {
        redisTemplate.opsForValue().set("interview:" + sessionId + ":termination",
                "{\"cause\":\"%s\",\"markedAt\":\"2026-07-31T10:00:00Z\"}".formatted(cause));
    }

    /** 파싱 불가 표식 시딩 — 존재 자체가 신호(판별 ②)임을 검증하는 입력. */
    void seedUnparseableMarker(long sessionId) {
        redisTemplate.opsForValue().set("interview:" + sessionId + ":termination", "not-a-json");
    }

    /** 시각 앵커 연기 — started_at·end_requested_at·agent_lost_at 등 전이 앵커를 직접 기록한다. */
    void setSessionInstant(long sessionId, String column, Instant value) {
        jdbcTemplate.update("UPDATE interview_session SET " + column + " = ? WHERE id = ?",
                Timestamp.from(value), sessionId);
    }

    /** 대조 스텁 매칭용 identity — 파생 규칙의 소유자(CandidateIdentity)에 위임해 규칙 변경 시 함께 움직인다. */
    String candidateOf(long sessionId) {
        return com.aisw.kkori.session.service.CandidateIdentity.of(sessionId);
    }

    Instant sessionInstant(long sessionId, String column) {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM interview_session WHERE id = ?", Timestamp.class, sessionId);
        return value == null ? null : value.toInstant();
    }
}
