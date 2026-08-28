package com.aisw.kkori.session.service;

import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.dto.AudioAnalysisRequestedMessage;
import com.aisw.kkori.session.repositoryservice.SessionRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 녹음 업로드 완료({@code egress_ended}·EGRESS_COMPLETE) 처리 — 세션 기록·음성 분석 요청 발행
 * (PRD interview-recording.md 기능 2·3).
 *
 * <p><b>순서는 발행 → 기록(PRD 확정, 2026-08-11)</b>: 발행 실패는 warn 후 기록을 생략하고 웹훅
 * 응답 200을 유지한다 — 멱등 가드(objectKey)가 남지 않아 webhook 재전송이 재발행 기회를 가진다.
 * 기록 실패는 전파(500)로 재전송을 유도한다 — 중복 발행은 at-least-once 소비 계약(워커의
 * sessionId 기준 멱등 처리)이 흡수한다.
 *
 * <p>상태 머신과 독립이다 — 세션이 이미 terminal이어도 동작하며({@code room_finished} 뒤 도착),
 * 전이 경로와 달리 user 행 잠금을 선행하지 않는다(다투는 컬럼이 없고, 중복 webhook 경합은
 * {@code recording_object_key IS NULL} 술어가 원자적으로 거른다). 발행은 트랜잭션 밖에서 한다 —
 * Redis 왕복 동안 DB 커넥션을 들지 않는다(생성 경로와 동일 방침).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRecordingService {

    private final SessionRepositoryService sessionRepositoryService;
    private final AudioAnalysisRequestPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 업로드 완료된 녹음을 세션에 기록하고 음성 분석 요청을 발행한다 — egress_ended 어댑터 분기의 종착. */
    public void completeRecording(String egressId, String bucket, String objectKey) {
        Optional<InterviewSession> found = sessionRepositoryService.findByEgressId(egressId);
        if (found.isEmpty()) {
            log.info("미등록 egress webhook — no-op (egressId={})", egressId);
            return;
        }
        InterviewSession session = found.get();
        if (session.getRecordingObjectKey() != null) {
            log.debug("녹음 기록 완료 세션 — 멱등 no-op (sessionId={}, egressId={})",
                    session.getId(), egressId);
            return;
        }
        try {
            publisher.publish(new AudioAnalysisRequestedMessage(session.getId(), bucket, objectKey));
        } catch (RuntimeException e) {
            log.warn("음성 분석 요청 발행 실패 — 기록 생략, webhook 재전송이 재발행 기회 (sessionId={}, egressId={})",
                    session.getId(), egressId, e);
            return;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Integer updated = transactionTemplate.execute(status ->
                sessionRepositoryService.recordRecordingResult(session.getId(), bucket, objectKey, now));
        if (updated != null && updated == 1) {
            log.info("녹음 업로드 기록·음성 분석 요청 발행 (sessionId={}, bucket={}, objectKey={})",
                    session.getId(), bucket, objectKey);
        } else {
            log.debug("동시 중복 webhook이 기록 선점 — no-op (sessionId={})", session.getId());
        }
    }
}
