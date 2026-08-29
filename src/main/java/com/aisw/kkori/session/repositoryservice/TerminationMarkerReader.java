package com.aisw.kkori.session.repositoryservice;

import com.aisw.kkori.session.dto.TerminationMarker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 에이전트 종료 표식(Redis)의 읽기 전용 조회 (크로스 레포 계약 — Kkori-AI interview-end.md §3).
 *
 * <p>키 {@code interview:{sessionId}:termination}, 값 JSON {@code {"cause","markedAt"}},
 * TTL 24h(에이전트 관리). Spring은 읽기만 한다 — TTL 연장·삭제를 포함해 어떤 쓰기도 하지 않는다.
 *
 * <p><b>조회 실패는 표식 부재로 취급한다</b>(경고 로그) — 판별 ③으로 후퇴해도 유예 후
 * ABORTED로 같은 결과에 수렴하며, 잘못된 ENDED를 만들지 않는 안전한 방향의 후퇴다(PRD 기능 3).
 * 반대로 값 파싱 실패는 <b>존재</b>로 취급한다 — 표식의 존재가 신호이고 cause는 진단용이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerminationMarkerReader {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 표식 조회 — empty는 "부재 또는 조회 실패"(둘 다 판별 ③으로 수렴)다. */
    public Optional<TerminationMarker> read(long sessionId) {
        String value;
        try {
            value = redisTemplate.opsForValue().get("interview:" + sessionId + ":termination");
        } catch (RuntimeException e) {
            log.warn("종료 표식 조회 실패 — 부재로 취급, 판별 ③ 후퇴 (sessionId={}): {}",
                    sessionId, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(parse(sessionId, value));
    }

    private TerminationMarker parse(long sessionId, String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            return new TerminationMarker(node.path("cause").asText(null), node.path("markedAt").asText(null));
        } catch (Exception e) {
            log.warn("종료 표식 파싱 실패 — 존재로 취급 (sessionId={})", sessionId);
            return TerminationMarker.unparseable();
        }
    }
}
