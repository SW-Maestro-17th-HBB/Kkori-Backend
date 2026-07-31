package com.aisw.kkori.session.service;

import com.aisw.kkori.session.config.SessionProperties;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.AgentPresence;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.session.repository.InterviewTranscriptReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 세션 수렴 스위퍼 (PRD interview-session-completion.md 공통: 스위퍼·설정).
 *
 * <p>webhook 없이도 모든 도달 가능 non-terminal 상태가 유한 시간 내 terminal로 수렴하는
 * 안전망이다 — 대상 시각이 전부 DB 컬럼에 있어 <b>서버 재시작에도 감시가 유실되지 않는다</b>
 * (인메모리 타이머 없음). 네 스캔을 한 주기로 실행한다:
 *
 * <ol>
 * <li><b>fallback</b>: /end 수리 후 room_finished 부재 ACTIVE — 행 판별 선기록 후 룸 삭제(계약 순서)</li>
 * <li><b>유예 만료</b>: AGENT_LOST — ABORTED + 룸 삭제(없으면 재디스패치 스토리 전까지 생성이 409로 막힌다)</li>
 * <li><b>stale ACTIVE</b>: webhook 최종 유실 회수 — 판별 재실행(③은 AGENT_LOST 아닌 즉시 ABORTED)</li>
 * <li><b>stale PENDING</b>: 동일 판별 + 판별 ③은 룸 참가자 대조 — AGENT 관측 시 ACTIVE 복원
 *     (joined 유실의 관측 기반 보정, 이후 stale ACTIVE가 hard ceiling)</li>
 * </ol>
 *
 * <p>세션별 처리는 독립 트랜잭션(user 잠금 + 조건부 UPDATE)으로 격리한다 — 한 세션의 실패가
 * 다른 세션 처리를 막지 않고, 다중 인스턴스 동시 실행도 조건부 UPDATE가 무해화한다. LiveKit
 * 왕복(룸 삭제·참가자 조회)은 트랜잭션·잠금 밖에서 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSweeper {

    /**
     * stale PENDING 대조 건너뛰기의 상한 배수 — {@code created_at}이 stale 임계의 이 배수(기본 3h)를
     * 넘기면 지속 장애로 대조가 계속 실패해도 대조 없이 행·표식 판별만으로 terminal을 확정한다.
     * 튜닝 노브가 아닌 안전 백스톱이라 설정이 아닌 상수로 둔다(PRD 기능 3 — 수렴을 LiveKit
     * 가용성에 조건부로 만들지 않는 hard ceiling).
     */
    private static final int STALE_PENDING_PROBE_CEILING_MULTIPLIER = 4;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewTranscriptReader transcriptReader;
    private final TerminationMarkerReader markerReader;
    private final SessionRoomManager roomManager;
    private final SessionTransitionExecutor transitionExecutor;
    private final SessionProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${session.sweep-interval}")
    public void sweep() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        sweepEndFallback(now);
        sweepAgentLostGrace(now);
        sweepStaleActive(now);
        sweepStalePending(now);
    }

    /** fallback — 선기록(행 판별) 후 룸 삭제. 순서는 크로스 레포 계약이다(선기록 없으면 fallback
     * 삭제의 room_finished가 정상 종료로 오판된다). */
    private void sweepEndFallback(Instant now) {
        Instant cutoff = now.minus(properties.endFallbackTimeout());
        forEachIsolated("fallback",
                sessionRepository.findByStatusAndEndRequestedAtLessThanEqual(SessionStatus.ACTIVE, cutoff),
                session -> {
                    SessionStatus to = arbitrateTerminal(session.getId(), "fallback");
                    int updated = transitionExecutor.execute(session.getUserId(),
                            txNow -> sessionRepository.finishFrom(
                                    session.getId(), Set.of(SessionStatus.ACTIVE), to, txNow));
                    if (updated == 1) {
                        log.info("fallback 만료 — {} 선기록 후 룸 삭제 (sessionId={})", to, session.getId());
                        roomManager.deleteRoomQuietly(session.getLivekitRoom());
                    }
                });
    }

    /** AGENT_LOST 유예 만료 — ABORTED 정리 + 룸 삭제(잔류 candidate는 ROOM_DELETED로 퇴장). */
    private void sweepAgentLostGrace(Instant now) {
        Instant cutoff = now.minus(properties.agentLostGrace());
        forEachIsolated("유예 만료",
                sessionRepository.findByStatusAndAgentLostAtLessThanEqual(SessionStatus.AGENT_LOST, cutoff),
                session -> {
                    int updated = transitionExecutor.execute(session.getUserId(),
                            txNow -> sessionRepository.finishFrom(
                                    session.getId(), Set.of(SessionStatus.AGENT_LOST), SessionStatus.ABORTED, txNow));
                    if (updated == 1) {
                        log.info("AGENT_LOST 유예 만료 — ABORTED 정리 (sessionId={})", session.getId());
                        roomManager.deleteRoomQuietly(session.getLivekitRoom());
                    }
                });
    }

    /**
     * stale ACTIVE — webhook 최종 유실 회수. 판별 ③은 AGENT_LOST가 아니라 즉시 ABORTED다(면접
     * 상한을 한참 지나 재dispatch가 무의미). end_requested_at 있는 세션은 fallback 전담이라
     * 후보 조회·전이 술어 모두에서 제외된다.
     */
    private void sweepStaleActive(Instant now) {
        Instant cutoff = now.minus(properties.staleRecoveryTimeout());
        forEachIsolated("stale ACTIVE",
                sessionRepository.findByStatusAndEndRequestedAtIsNullAndStartedAtLessThanEqual(
                        SessionStatus.ACTIVE, cutoff),
                session -> {
                    SessionStatus to = arbitrateTerminal(session.getId(), "stale ACTIVE");
                    int updated = transitionExecutor.execute(session.getUserId(),
                            txNow -> sessionRepository.finishStaleActive(session.getId(), to, txNow));
                    if (updated == 1) {
                        log.info("stale ACTIVE 회수 — {} (sessionId={})", to, session.getId());
                        roomManager.deleteRoomQuietly(session.getLivekitRoom());
                    }
                });
    }

    /**
     * stale PENDING — PENDING은 관측 상태일 뿐이라(joined 유실 병리) 행·표식이 존재할 수 있어
     * 같은 판별을 적용하고, 판별 ③은 정리 전에 룸 참가자를 대조한다: AGENT 관측 시 ACTIVE로
     * 복원(started_at = 관측 입장 시각, 부재 시 현재 시각 보수 적용 — 이후 stale ACTIVE가 hard
     * ceiling), 룸 미존재·AGENT 없음은 확정 증거로 정리, 조회 실패는 이번 회차만 건너뛴다.
     */
    private void sweepStalePending(Instant now) {
        Instant cutoff = now.minus(properties.staleRecoveryTimeout());
        Instant probeCeiling = now.minus(
                properties.staleRecoveryTimeout().multipliedBy(STALE_PENDING_PROBE_CEILING_MULTIPLIER));
        forEachIsolated("stale PENDING",
                sessionRepository.findByStatusAndCreatedAtLessThanEqual(SessionStatus.PENDING, cutoff),
                session -> {
                    if (transcriptReader.exists(session.getId())) {
                        finishStalePending(session, SessionStatus.ENDED);
                        return;
                    }
                    if (markerReader.read(session.getId()).isPresent()) {
                        finishStalePending(session, SessionStatus.ABORTED);
                        return;
                    }
                    // hard ceiling — 대조 없이 확정한다. 토큰 TTL(1h)이 늦은 입장을 차단해 이 시점의
                    // 진행 중 면접은 실질 불가하고, 판별(DB·Redis)은 대조(LiveKit)와 장애 도메인이 분리된다
                    if (!session.getCreatedAt().isAfter(probeCeiling)) {
                        log.info("stale PENDING 대조 상한 초과 — 대조 없이 정리 (sessionId={})", session.getId());
                        finishStalePending(session, SessionStatus.ABORTED);
                        return;
                    }
                    AgentPresence presence = roomManager.probeAgentPresence(session.getLivekitRoom());
                    switch (presence.status()) {
                        case PRESENT -> restoreActive(session, presence.joinedAt());
                        case ABSENT -> finishStalePending(session, SessionStatus.ABORTED);
                        case UNKNOWN -> log.info("stale PENDING 참가자 조회 실패 — 다음 스위프 재시도 (sessionId={})",
                                session.getId());
                    }
                });
    }

    private void finishStalePending(InterviewSession session, SessionStatus to) {
        int updated = transitionExecutor.execute(session.getUserId(),
                txNow -> sessionRepository.finishFrom(
                        session.getId(), Set.of(SessionStatus.PENDING), to, txNow));
        if (updated == 1) {
            log.info("stale PENDING 회수 — {} (sessionId={})", to, session.getId());
            roomManager.deleteRoomQuietly(session.getLivekitRoom());
        }
    }

    /** joined 유실의 관측 기반 보정 — 복원 후에는 started_at 앵커의 stale ACTIVE가 수렴을 담당한다. */
    private void restoreActive(InterviewSession session, Instant observedJoinedAt) {
        int updated = transitionExecutor.execute(session.getUserId(), txNow -> {
            Instant startedAt = observedJoinedAt != null ? observedJoinedAt : txNow;
            return sessionRepository.activate(session.getId(), startedAt, txNow);
        });
        if (updated == 1) {
            log.info("stale PENDING의 AGENT 관측 — ACTIVE 복원 (sessionId={}, joinedAt={})",
                    session.getId(), observedJoinedAt);
        }
    }

    /** terminal 확정 판별 — 행 있음 ENDED, 없음 ABORTED(표식은 진단 로그만 — cause 불분기 계약). */
    private SessionStatus arbitrateTerminal(long sessionId, String path) {
        if (transcriptReader.exists(sessionId)) {
            return SessionStatus.ENDED;
        }
        markerReader.read(sessionId).ifPresent(marker ->
                log.info("{} 판별 — 표식 관측 (sessionId={}, cause={}, markedAt={})",
                        path, sessionId, marker.cause(), marker.markedAt()));
        return SessionStatus.ABORTED;
    }

    /** 세션별 격리 — 한 세션의 실패가 같은 스캔의 다른 세션 처리를 막지 않는다. */
    private void forEachIsolated(String scan, List<InterviewSession> sessions, Consumer<InterviewSession> action) {
        for (InterviewSession session : sessions) {
            try {
                action.accept(session);
            } catch (RuntimeException e) {
                log.warn("{} 스캔 세션 처리 실패 — 다음 스위프 재시도 (sessionId={}): {}",
                        scan, session.getId(), e.getClass().getSimpleName());
            }
        }
    }
}
