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

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
        List<TranscriptUtterance> utterances;
        try {
            utterances = objectMapper.readValue(rows.get(0), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            log.error("대본 JSON 파싱 실패 (session_id={})", sessionId, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (utterances == null) {
            // JSON 리터럴 null 은 파싱 예외가 아니라 자바 null 로 돌아온다 ('null'::jsonb 는 NOT NULL 통과)
            log.error("대본 content가 JSON null (session_id={})", sessionId);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        validate(utterances, sessionId);
        // questionNumber 없는 발화(closing 인사)는 어느 질문-답변 쌍에도 속하지 않으므로
        // 타임라인 조립에서 제외하고, 에이전트 실물 직렬화(HBB1-287)의 speaker CANDIDATE는
        // 계약 값 USER로 정규화한다 — 값 집합 합의(PRD §1 기타 미정) 전 읽기 측 흡수
        // (Kkori-AI Worker의 load_transcript와 동일 정책, 확정 시 함께 제거).
        List<TranscriptUtterance> paired = utterances.stream()
                .filter(u -> u.questionNumber() != null)
                .map(JdbcTranscriptReader::normalizeSpeaker)
                .toList();
        return Optional.of(paired);
    }

    private static TranscriptUtterance normalizeSpeaker(TranscriptUtterance u) {
        if (!"CANDIDATE".equals(u.speaker())) {
            return u;
        }
        return new TranscriptUtterance(u.questionNumber(), u.parentQuestionNumber(),
                TranscriptUtterance.SPEAKER_USER, u.questionType(), u.content(), u.spokenAt());
    }

    /**
     * 필드 수준 계약 검증 — 대본은 다른 도메인이 쓰는 데이터라, 위반을 조립 중의
     * 원시 예외(NPE 등)로 흘리지 않고 경계에서 명확한 500으로 변환한다.
     * questionNumber는 검증하지 않는다 — 없는 발화(closing 인사)는 위반이 아니라 제외 대상.
     */
    private static void validate(List<TranscriptUtterance> utterances, long sessionId) {
        for (TranscriptUtterance u : utterances) {
            if (u == null || u.content() == null || !hasParseableSpokenAt(u)) {
                // 발화 내용(content)은 사용자 답변이라 로그에 남기지 않는다
                log.error("대본 발화가 계약을 위반 (session_id={}, questionNumber={}, spokenAt={})",
                        sessionId, u == null ? null : u.questionNumber(),
                        u == null ? null : u.spokenAt());
                throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
            }
        }
    }

    private static boolean hasParseableSpokenAt(TranscriptUtterance u) {
        if (u.spokenAt() == null) {
            return false;
        }
        try {
            OffsetDateTime.parse(u.spokenAt());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
