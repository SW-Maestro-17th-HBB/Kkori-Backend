package com.aisw.kkori.report.repositoryservice;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.report.dto.TranscriptUtterance;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 대본 네이티브 조회 구현 — 엔티티 매핑 없이 content JSON만 꺼낸다.
 *
 * <p>테이블은 에이전트가 소유·마이그레이션한다(Kkori-AI agent/migrations/001_interview_transcript.sql).
 * 남의 스키마를 JPA 엔티티로 복제하지 않기 위한 최소 결합 — 접근 방식 팀 합의 시 교체 후보.
 * deleted_at은 면접 도메인의 삭제 정책 확정 전이라 조건에 넣지 않는다(PRD §4 기타).
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JdbcTranscriptReader implements TranscriptReader {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<List<TranscriptUtterance>> findUtterances(long sessionId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT content::text FROM interview_transcript WHERE session_id = ?",
                String.class, sessionId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(rows.get(0), new TypeReference<>() {
            }));
        } catch (JsonProcessingException e) {
            log.error("대본 JSON 파싱 실패 (session_id={})", sessionId, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
