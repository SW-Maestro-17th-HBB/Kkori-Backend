package com.aisw.kkori.session.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.AgentPresence;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.session.repository.InterviewTranscriptReader;
import com.aisw.kkori.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * 사용자 명시 종료 (PRD interview-session-completion.md 기능 2).
 *
 * <p>{@code /end}는 "이 세션을 끝내달라"는 의도 표명이며 <b>상태와 무관하게 terminal 수렴을
 * 보장</b>한다 — 상태별로 종료 방법이 다를 뿐이다: ACTIVE는 SendData로 에이전트 정상 종료를
 * 유도하고(실제 확정은 room_finished, 미처리는 fallback 스위퍼가 수렴), 소실 관측
 * 상태(AGENT_LOST·INTERRUPTED)는 즉시 ABORTED + 룸 삭제, terminal은 멱등 no-op에 룸 삭제만
 * 재시도한다(선기록 후 삭제 실패로 잔존한 룸의 기회적 복구 경로).
 *
 * <p><b>PENDING은 관측 상태일 뿐이라 에이전트 부재를 단정하지 않는다</b>(joined 유실 병리 —
 * stale PENDING과 동일 보호): 행 있음 → ENDED, 행 없음 → 룸 참가자 대조로 AGENT 관측 시
 * ACTIVE 복원 후 SendData(정상 클로징·flush·리포트 보존), 부재 확정 시 ABORTED, 대조 실패는
 * 종료를 단정하지 않고 S008(재시도 가능 — 수렴은 스위퍼·room_finished가 어차피 보장). 대조는
 * 잠금 트랜잭션 밖(LiveKit 왕복)이므로 반영 트랜잭션이 최신 상태를 재분기한다 — 대조 결과는
 * PENDING 유지 시에만 유효하다.
 *
 * <p>응답(202)은 수리(종료 수렴 보장)의 의미다. LiveKit 왕복(대조·SendData·룸 삭제)은
 * 트랜잭션·잠금 밖에서 한다 — 기존 생성 파이프라인과 동일 방침.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionEndService {

    /** 소실이 관측된 상태(판별 ③ 경유) — 부재가 증거 기반이라 대조 없이 즉시 정리한다. */
    private static final Set<SessionStatus> LOSS_OBSERVED =
            Set.of(SessionStatus.AGENT_LOST, SessionStatus.INTERRUPTED);

    private final InterviewSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final InterviewTranscriptReader transcriptReader;
    private final SessionEndSignalSender endSignalSender;
    private final SessionRoomManager roomManager;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 세션 종료 요청을 수리한다 — 소유자만, 상태 무관. */
    public void end(Long userId, Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.SESSION_FORBIDDEN);
        }

        // [트랜잭션] user 잠금 하에 상태를 재판정하고 기록한다 — 잠금 획득 시점의 상태 기준.
        EndAction action = transactionTemplate.execute(status -> planInTransaction(userId, sessionId, null));

        if (action == EndAction.PROBE_PENDING) {
            // [트랜잭션 밖] PENDING 대조 — 행 판별 → 룸 참가자 대조 후 재진입(최신 상태 재분기)
            PendingResolution resolution = resolvePending(session);
            action = transactionTemplate.execute(status -> planInTransaction(userId, sessionId, resolution));
        }

        // [커밋 후] LiveKit 왕복 — SendData 실패(S008)는 응답으로 표면화하되, 종료 의도는 이미
        // 커밋되어 있어 재시도 없이도 fallback이 종료를 보장한다.
        switch (action) {
            case SEND_END_SIGNAL -> {
                endSignalSender.send(session.getLivekitRoom(), sessionId);
                log.info("종료 신호 발신 — room_finished 대기, fallback 감시 (sessionId={})", sessionId);
            }
            case CLEAN_ROOM -> roomManager.deleteRoomQuietly(session.getLivekitRoom());
            case PROBE_PENDING -> throw new IllegalStateException("대조는 재진입에서 해소되어야 한다");
        }
    }

    private EndAction planInTransaction(Long userId, Long sessionId, PendingResolution resolution) {
        // 활성 재확인 없는 잠금 — 소유 검증은 이미 끝났고, 전이 직렬화만 필요하다(전이 경로 공통)
        userRepository.findWithLockById(userId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        return switch (session.getStatus()) {
            case ACTIVE -> {
                // first-wins — 중복 /end는 재발신하되(에이전트 처리는 멱등) fallback 창을 연장하지 않는다
                sessionRepository.recordEndRequested(sessionId, now);
                yield EndAction.SEND_END_SIGNAL;
            }
            case PENDING -> planPending(sessionId, resolution, now);
            case AGENT_LOST, INTERRUPTED -> {
                sessionRepository.finishFrom(sessionId, LOSS_OBSERVED, SessionStatus.ABORTED, now);
                log.info("소실 관측 세션 즉시 종료 — {} → ABORTED (sessionId={})", session.getStatus(), sessionId);
                yield EndAction.CLEAN_ROOM;
            }
            // terminal — 멱등 no-op + 룸 삭제 재시도(잔존 룸 기회적 복구)
            case ENDED, ABORTED -> EndAction.CLEAN_ROOM;
        };
    }

    private EndAction planPending(Long sessionId, PendingResolution resolution, Instant now) {
        if (resolution == null) {
            return EndAction.PROBE_PENDING;
        }
        return switch (resolution.kind()) {
            case TRANSCRIPT_EXISTS -> {
                sessionRepository.finishFrom(sessionId, Set.of(SessionStatus.PENDING), SessionStatus.ENDED, now);
                log.info("PENDING /end — 행 존재, ENDED 확정 (sessionId={})", sessionId);
                yield EndAction.CLEAN_ROOM;
            }
            case AGENT_PRESENT -> {
                // joined 유실의 관측 기반 보정 — 복원 후 ACTIVE와 동일 처리(정상 클로징 보존)
                Instant startedAt = resolution.joinedAt() != null ? resolution.joinedAt() : now;
                sessionRepository.activate(sessionId, startedAt, now);
                sessionRepository.recordEndRequested(sessionId, now);
                log.info("PENDING /end — AGENT 관측, ACTIVE 복원 후 종료 신호 (sessionId={})", sessionId);
                yield EndAction.SEND_END_SIGNAL;
            }
            case AGENT_ABSENT -> {
                sessionRepository.finishFrom(sessionId, Set.of(SessionStatus.PENDING), SessionStatus.ABORTED, now);
                log.info("PENDING /end — 부재 확정, ABORTED (sessionId={})", sessionId);
                yield EndAction.CLEAN_ROOM;
            }
        };
    }

    /** PENDING 대조 — 행 판별을 먼저(대조 불필요한 확정 증거), 그 다음 룸 참가자 대조. */
    private PendingResolution resolvePending(InterviewSession session) {
        if (transcriptReader.exists(session.getId())) {
            return PendingResolution.transcriptExists();
        }
        AgentPresence presence = roomManager.probeAgentPresence(session.getLivekitRoom());
        return switch (presence.status()) {
            case PRESENT -> PendingResolution.agentPresent(presence.joinedAt());
            case ABSENT -> PendingResolution.agentAbsent();
            // 종료를 단정하지 않는다 — 재시도 가능, 수렴은 스위퍼·room_finished가 보장
            case UNKNOWN -> throw new BusinessException(ErrorCode.SESSION_END_SIGNAL_FAILED);
        };
    }

    private enum EndAction { SEND_END_SIGNAL, CLEAN_ROOM, PROBE_PENDING }

    private record PendingResolution(Kind kind, Instant joinedAt) {
        enum Kind { TRANSCRIPT_EXISTS, AGENT_PRESENT, AGENT_ABSENT }

        static PendingResolution transcriptExists() {
            return new PendingResolution(Kind.TRANSCRIPT_EXISTS, null);
        }

        static PendingResolution agentPresent(Instant joinedAt) {
            return new PendingResolution(Kind.AGENT_PRESENT, joinedAt);
        }

        static PendingResolution agentAbsent() {
            return new PendingResolution(Kind.AGENT_ABSENT, null);
        }
    }
}
