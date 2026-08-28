package com.aisw.kkori.session.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.config.SessionProperties;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.InterviewSessionCreateResponse;
import com.aisw.kkori.session.repositoryservice.SessionRepositoryService;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 재입장 토큰 발급 (PRD interview-session-reconnection.md 기능 2).
 *
 * <p><b>identity 동일 보장</b>: 최초와 같은 {@code candidate-{sessionId}}(결정적 파생) —
 * 에이전트의 재개 판정(identity 일치)이 이 보장 위에 성립하고, 잔존 유령 연결은 LiveKit의
 * DUPLICATE_IDENTITY 강제 퇴장이 정리한다.
 *
 * <p><b>TTL = 재연결 deadline 기준</b>: {@code disconnected_at + reconnect-window − now}(동적,
 * 계약). 고정 TTL이 잔여 deadline을 넘으면 창 소진 후에도 토큰이 살아 좀비 입장 창이 열린다.
 *
 * <p><b>무상태 발급</b>: 상태 전이·기록 없음 — 중복 발급은 무해하다(모든 토큰이 같은
 * identity·같은 deadline). 검증은 user 잠금 트랜잭션에서 해 유예 스위퍼의 ABORTED와
 * 직렬화하고, 서명(로컬 연산)은 트랜잭션 밖에서 한다. 발급 직후 세션이 닫히는 잔여 경합은
 * 감수한다 — 고아 룸 위험·프론트 완화 계약은 PRD 참조(AGENT 미관측 무한 대기 금지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRejoinService {

    private final SessionRepositoryService sessionRepositoryService;
    private final UserRepositoryService userRepositoryService;
    private final SessionTicketIssuer ticketIssuer;
    private final SessionProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 재입장 토큰을 발급한다 — {@code INTERRUPTED}·{@code AGENT_LOST}(이탈 관측된 경우)에서
     * 재연결 deadline 전까지만. AGENT_LOST 포함 이유: 에이전트 소실·재디스패치 진행 중에도
     * candidate 재입장은 유효하다 — 입장해 두면 joined(agent) 대조가 ACTIVE로 수렴시킨다.
     */
    public InterviewSessionCreateResponse rejoin(Long userId, Long sessionId) {
        InterviewSession session = sessionRepositoryService.findOwned(userId, sessionId);

        // [트랜잭션] user 잠금 하에 발급 조건 검증 — 유예 스위퍼의 ABORTED와 직렬화된다.
        Instant deadline = transactionTemplate.execute(status -> validateInTransaction(userId, sessionId));

        // [트랜잭션 밖] 로컬 서명 — 검증~서명 사이 잔여 TTL이 소진되는 극단 경합은 S009로 끝낸다.
        Duration ttl = Duration.between(clock.instant(), deadline);
        if (ttl.isZero() || ttl.isNegative()) {
            throw new BusinessException(ErrorCode.SESSION_NOT_REJOINABLE);
        }
        SessionTicket ticket = ticketIssuer.issue(
                CandidateIdentity.of(sessionId), session.getLivekitRoom(), ttl);
        log.info("재입장 토큰 발급 (sessionId={}, 잔여 TTL={}s)", sessionId, ttl.toSeconds());
        return new InterviewSessionCreateResponse(
                sessionId, ticket.token(), ticket.serverUrl(), session.getLivekitRoom());
    }

    private Instant validateInTransaction(Long userId, Long sessionId) {
        userRepositoryService.lockUser(userId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        InterviewSession session = sessionRepositoryService.getById(sessionId);
        boolean rejoinableStatus = session.getStatus() == SessionStatus.INTERRUPTED
                || session.getStatus() == SessionStatus.AGENT_LOST;
        // 사유(상태 부적합·이탈 미관측·창 만료)는 로그로만 구분한다 — S009 시점은 전부 "재시도
        // 무의미"라 프론트는 일괄 종료 화면 처리(계약, 메시지 자구 비의존)
        if (!rejoinableStatus || session.getDisconnectedAt() == null) {
            log.info("재입장 거부 — 상태 부적합·이탈 미관측 (sessionId={}, status={}, disconnectedAt={})",
                    sessionId, session.getStatus(), session.getDisconnectedAt());
            throw new BusinessException(ErrorCode.SESSION_NOT_REJOINABLE);
        }
        Instant deadline = session.getDisconnectedAt().plus(properties.reconnectWindow());
        if (!now.isBefore(deadline)) {
            log.info("재입장 거부 — 재연결 창 만료 (sessionId={}, deadline={})", sessionId, deadline);
            throw new BusinessException(ErrorCode.SESSION_NOT_REJOINABLE);
        }
        return deadline;
    }
}
