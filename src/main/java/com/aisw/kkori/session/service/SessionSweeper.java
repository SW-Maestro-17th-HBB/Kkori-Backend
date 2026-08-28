package com.aisw.kkori.session.service;

import com.aisw.kkori.session.config.SessionProperties;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.AgentPresence;
import com.aisw.kkori.session.dto.RoomPresence;
import com.aisw.kkori.session.repositoryservice.SessionRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 세션 수렴 스위퍼 (PRD interview-session-completion.md · interview-session-reconnection.md).
 *
 * <p>webhook 없이도 모든 도달 가능 non-terminal 상태가 유한 시간 내 terminal로 수렴하는
 * 안전망이다 — 대상 시각이 전부 DB 컬럼에 있어 <b>서버 재시작에도 감시가 유실되지 않는다</b>
 * (인메모리 타이머 없음). 다섯 스캔을 한 주기로 실행한다:
 *
 * <ol>
 * <li><b>fallback</b>: /end 수리 후 room_finished 부재 ACTIVE·INTERRUPTED — 행 판별 선기록 후
 *     룸 삭제(계약 순서)</li>
 * <li><b>INTERRUPTED 유예</b>: 재연결 창 + 마진 경과 — 판별 선행(행→ENDED) 후 룸 대조
 *     (candidate+AGENT → ACTIVE 복원 / candidate만 → skip / 부재 → ABORTED, skip 상한 =
 *     유예 컷오프 간격(창+마진)의 4배)</li>
 * <li><b>유예 만료</b>: AGENT_LOST — ABORTED + 룸 삭제(없으면 생성이 409로 계속 막힌다)</li>
 * <li><b>stale ACTIVE</b>: webhook 최종 유실 회수 — 판별 재실행(③은 즉시 ABORTED하되 룸 대조로
 *     진행 중 면접 보호 — 재연결로 벽시계 체류가 45m를 넘을 수 있다, skip 상한 = 임계의 4배)</li>
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
     * 대조 건너뛰기(skip)의 상한 배수 — 기준 시각이 각 스캔 임계의 이 배수를 넘기면 지속
     * 장애·관측 모호로 대조가 계속 미뤄져도 대조 없이 행·표식 판별만으로 terminal을 확정한다.
     * 튜닝 노브가 아닌 안전 백스톱이라 설정이 아닌 상수로 둔다(수렴을 LiveKit 가용성에
     * 조건부로 만들지 않는 hard ceiling — stale PENDING·stale ACTIVE·INTERRUPTED 공통 패턴).
     */
    private static final int PROBE_CEILING_MULTIPLIER = 4;

    /**
     * INTERRUPTED 유예 마진 — 재연결 창(계약값)에 더해 스위퍼가 기다리는 코드 상수.
     * 별도 설정으로 두지 않아 계약값과의 정합이 구조적으로 보장된다. 값 근거: 양측 이탈 관측
     * 시각 편차(수 초) + 에이전트 창 소진 처리·룸 삭제 bounded retry 최악 34s(HBB1-294 실측
     * 상수)에 여유를 둔 값 — 창보다 짧으면 에이전트가 정당하게 기다리는 재연결 창을 Spring이
     * 박탈한다(재연결 PRD 값 정합 표).
     */
    private static final Duration INTERRUPTED_GRACE_MARGIN = Duration.ofSeconds(45);

    private final SessionRepositoryService sessionRepositoryService;
    private final SessionRoomManager roomManager;
    private final SessionTransitionExecutor transitionExecutor;
    private final SessionProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${session.sweep-interval}")
    public void sweep() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        sweepEndFallback(now);
        sweepInterruptedGrace(now);
        sweepAgentLostGrace(now);
        sweepStaleActive(now);
        sweepStalePending(now);
    }

    /** fallback — 선기록(행 판별) 후 룸 삭제. 순서는 크로스 레포 계약이다(선기록 없으면 fallback
     * 삭제의 room_finished가 정상 종료로 오판된다). INTERRUPTED 포함(HBB1-308 — /end의 ACTIVE
     * 동일 취급 확장). */
    private void sweepEndFallback(Instant now) {
        Instant cutoff = now.minus(properties.endFallbackTimeout());
        Set<SessionStatus> targets = Set.of(SessionStatus.ACTIVE, SessionStatus.INTERRUPTED);
        forEachIsolated("fallback",
                sessionRepositoryService.findEndRequestedFallbackCandidates(targets, cutoff),
                session -> {
                    SessionStatus to = arbitrateTerminal(session.getId(), "fallback");
                    int updated = transitionExecutor.execute(session.getUserId(),
                            txNow -> sessionRepositoryService.finishFrom(session.getId(), targets, to, txNow));
                    if (updated == 1) {
                        log.info("fallback 만료 — {} 선기록 후 룸 삭제 (sessionId={})", to, session.getId());
                        roomManager.deleteRoomQuietly(session.getLivekitRoom());
                    }
                });
    }

    /**
     * INTERRUPTED 유예 — 재연결 창의 안전망(주 수렴은 에이전트의 창 소진 룸 삭제 → room_finished).
     * end_requested_at 있는 세션은 fallback 전담(후보·전이 술어 양쪽에서 제외). 처리는 판별
     * 재사용이 선행한다 — 창 소진의 정상 수렴이 room_finished 유실로 미반영됐을 수 있어 룸 부재
     * 단독으로 ABORTED를 단정하지 않는다(terminal 확정 원칙: ENDED ⇔ 행 존재).
     */
    private void sweepInterruptedGrace(Instant now) {
        // 상한은 유예 컷오프 간격(창+마진) 기준 — 창에만 곱하면 짧은 창 설정(≤마진/3)에서 상한이
        // 컷오프보다 앞서는 역전이 생겨 대조(복원 경로)가 통째로 사라진다
        Duration graceWindow = properties.reconnectWindow().plus(INTERRUPTED_GRACE_MARGIN);
        Instant cutoff = now.minus(graceWindow);
        Instant probeCeiling = now.minus(graceWindow.multipliedBy(PROBE_CEILING_MULTIPLIER));
        forEachIsolated("INTERRUPTED 유예",
                sessionRepositoryService.findInterruptedGraceExpired(cutoff),
                session -> {
                    if (sessionRepositoryService.transcriptExists(session.getId())) {
                        // 이탈 중 면접 시간 소진 → flush 정상 종료의 webhook 유실 보정
                        finishInterrupted(session, SessionStatus.ENDED);
                        return;
                    }
                    if (readMarkerLogged(session.getId(), "INTERRUPTED 유예")) {
                        finishInterrupted(session, SessionStatus.ABORTED);
                        return;
                    }
                    // skip 상한 — 사유 불문(candidate-only·대조 실패) 공통. 행·표식은 위에서 이미
                    // 부재 확인됐으므로 ABORTED 확정 (수렴의 LiveKit 가용성 비의존)
                    if (!session.getDisconnectedAt().isAfter(probeCeiling)) {
                        log.info("INTERRUPTED 대조 상한 초과 — 대조 없이 정리 (sessionId={})", session.getId());
                        finishInterrupted(session, SessionStatus.ABORTED);
                        return;
                    }
                    RoomPresence presence = roomManager.probeRoomPresence(
                            session.getLivekitRoom(), CandidateIdentity.of(session.getId()));
                    if (!presence.observed()) {
                        log.info("INTERRUPTED 대조 실패 — 다음 스위프 재시도 (sessionId={})", session.getId());
                        return;
                    }
                    if (presence.bothPresent()) {
                        // 가짜 INTERRUPTED(유령 left 역전·reason 미실림)의 최종 보정
                        resumeInterrupted(session);
                        return;
                    }
                    if (presence.candidatePresent()) {
                        // 에이전트 소실 webhook 지연·유실 가능 — 판별 경로(left(agent)·stale)에 양보
                        log.info("INTERRUPTED 대조 — candidate만 관측, 판별 경로 양보 (sessionId={})",
                                session.getId());
                        return;
                    }
                    finishInterrupted(session, SessionStatus.ABORTED);
                });
    }

    private void finishInterrupted(InterviewSession session, SessionStatus to) {
        int updated = transitionExecutor.execute(session.getUserId(),
                txNow -> sessionRepositoryService.finishInterruptedGrace(session.getId(), to, txNow));
        if (updated == 1) {
            log.info("INTERRUPTED 유예 만료 — {} 정리 (sessionId={})", to, session.getId());
            roomManager.deleteRoomQuietly(session.getLivekitRoom());
        }
    }

    private void resumeInterrupted(InterviewSession session) {
        int updated = transitionExecutor.execute(session.getUserId(),
                txNow -> sessionRepositoryService.resumeFromInterrupted(session.getId(), txNow));
        if (updated == 1) {
            log.info("INTERRUPTED 대조 — candidate+AGENT 관측, ACTIVE 복원 (sessionId={})", session.getId());
        }
    }

    /**
     * AGENT_LOST 유예 만료 — 정리 + 룸 삭제(잔류 candidate는 ROOM_DELETED로 퇴장). HBB1-308 개정:
     * <b>deadline 충돌 방지</b> — 이탈 관측(disconnected_at) 세션은 재연결 deadline(+마진)과의
     * 늦은 쪽까지 기다린다(발급된 재입장 토큰의 deadline 약속을 스위퍼가 선제 파기하지 않고,
     * 계류 dispatch의 뒤늦은 join + 재입장이 그 창 안에서 복구를 완성할 수 있다). <b>행 재판별</b> —
     * 재디스패치 복원 에이전트가 면접을 완료했는데 webhook이 전량 유실된 병리에서 정상 완료를
     * ABORTED로 오판하지 않는다(terminal 확정 원칙).
     */
    private void sweepAgentLostGrace(Instant now) {
        Instant cutoff = now.minus(properties.agentLostGrace());
        Instant reconnectCutoff = now.minus(properties.reconnectWindow().plus(INTERRUPTED_GRACE_MARGIN));
        forEachIsolated("유예 만료",
                sessionRepositoryService.findAgentLostGraceExpired(cutoff),
                session -> {
                    if (session.getDisconnectedAt() != null
                            && session.getDisconnectedAt().isAfter(reconnectCutoff)) {
                        return; // 재연결 deadline 미도래 — max(유예, deadline+마진)의 늦은 쪽 대기
                    }
                    SessionStatus to = sessionRepositoryService.transcriptExists(session.getId())
                            ? SessionStatus.ENDED : SessionStatus.ABORTED;
                    int updated = transitionExecutor.execute(session.getUserId(),
                            txNow -> sessionRepositoryService.finishFrom(
                                    session.getId(), Set.of(SessionStatus.AGENT_LOST), to, txNow));
                    if (updated == 1) {
                        // redispatched는 CAS 도달 여부만 말한다 — 결과 구분은 재디스패치 단계별 로그 correlation
                        log.info("AGENT_LOST 유예 만료 — {} 정리 (sessionId={}, redispatched={})",
                                to, session.getId(), session.getRedispatchedAt() != null);
                        roomManager.deleteRoomQuietly(session.getLivekitRoom());
                    }
                });
    }

    /**
     * stale ACTIVE — webhook 최종 유실 회수. 판별 ③은 AGENT_LOST가 아니라 즉시 ABORTED다(면접
     * 상한을 한참 지나 재dispatch가 무의미). 단 HBB1-308 개정 — ③은 확정 전 룸 대조를 거친다:
     * 재연결(이탈·복귀 반복, 에이전트의 면접 시계 정지)로 정상 세션의 벽시계 체류가 45m를 넘을
     * 수 있어, candidate+AGENT 동시 관측 시 이번 회차를 건너뛴다. skip 상한(임계의 4배)이 hang
     * 에이전트의 무한 skip을 차단한다(HBB1-294가 ACTIVE 대조를 두지 않았던 근거의 보존).
     * end_requested_at 있는 세션은 fallback 전담이라 후보 조회·전이 술어 모두에서 제외된다.
     */
    private void sweepStaleActive(Instant now) {
        Instant cutoff = now.minus(properties.staleRecoveryTimeout());
        Instant probeCeiling = now.minus(
                properties.staleRecoveryTimeout().multipliedBy(PROBE_CEILING_MULTIPLIER));
        forEachIsolated("stale ACTIVE",
                sessionRepositoryService.findStaleActive(cutoff),
                session -> {
                    if (sessionRepositoryService.transcriptExists(session.getId())) {
                        finishStaleActive(session, SessionStatus.ENDED);
                        return;
                    }
                    if (readMarkerLogged(session.getId(), "stale ACTIVE")) {
                        finishStaleActive(session, SessionStatus.ABORTED);
                        return;
                    }
                    // ③ — 진행 중 면접 보호 대조 (상한 내에서만)
                    if (session.getStartedAt().isAfter(probeCeiling)) {
                        RoomPresence presence = roomManager.probeRoomPresence(
                                session.getLivekitRoom(), CandidateIdentity.of(session.getId()));
                        if (!presence.observed()) {
                            log.info("stale ACTIVE 대조 실패 — 다음 스위프 재시도 (sessionId={})", session.getId());
                            return;
                        }
                        if (presence.bothPresent()) {
                            log.info("stale ACTIVE 대조 — 진행 중 면접 관측, 이번 회차 건너뜀 (sessionId={})",
                                    session.getId());
                            return;
                        }
                    } else {
                        log.info("stale ACTIVE 대조 상한 초과 — 대조 없이 정리 (sessionId={})", session.getId());
                    }
                    finishStaleActive(session, SessionStatus.ABORTED);
                });
    }

    private void finishStaleActive(InterviewSession session, SessionStatus to) {
        int updated = transitionExecutor.execute(session.getUserId(),
                txNow -> sessionRepositoryService.finishStaleActive(session.getId(), to, txNow));
        if (updated == 1) {
            log.info("stale ACTIVE 회수 — {} (sessionId={})", to, session.getId());
            roomManager.deleteRoomQuietly(session.getLivekitRoom());
        }
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
                properties.staleRecoveryTimeout().multipliedBy(PROBE_CEILING_MULTIPLIER));
        forEachIsolated("stale PENDING",
                sessionRepositoryService.findStalePending(cutoff),
                session -> {
                    if (sessionRepositoryService.transcriptExists(session.getId())) {
                        finishStalePending(session, SessionStatus.ENDED);
                        return;
                    }
                    if (readMarkerLogged(session.getId(), "stale PENDING")) {
                        finishStalePending(session, SessionStatus.ABORTED);
                        return;
                    }
                    // hard ceiling — 대조 없이 확정한다. 토큰 TTL(10m)이 늦은 입장을 차단해 이 시점의
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
                txNow -> sessionRepositoryService.finishFrom(
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
            return sessionRepositoryService.activate(session.getId(), startedAt, txNow);
        });
        if (updated == 1) {
            log.info("stale PENDING의 AGENT 관측 — ACTIVE 복원 (sessionId={}, joinedAt={})",
                    session.getId(), observedJoinedAt);
        }
    }

    /** terminal 확정 판별 — 행 있음 ENDED, 없음 ABORTED(표식은 진단 로그만 — cause 불분기 계약). */
    private SessionStatus arbitrateTerminal(long sessionId, String path) {
        if (sessionRepositoryService.transcriptExists(sessionId)) {
            return SessionStatus.ENDED;
        }
        readMarkerLogged(sessionId, path);
        return SessionStatus.ABORTED;
    }

    /** 표식 존재 판별 + cause 진단 로그 (분기 없음 — 크로스 레포 계약). */
    private boolean readMarkerLogged(long sessionId, String path) {
        return sessionRepositoryService.readTerminationMarker(sessionId).map(marker -> {
            log.info("{} 판별 — 표식 관측 (sessionId={}, cause={}, markedAt={})",
                    path, sessionId, marker.cause(), marker.markedAt());
            return true;
        }).orElse(false);
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
