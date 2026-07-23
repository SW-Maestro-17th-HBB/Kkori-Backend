package com.aisw.kkori.session.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.service.ResumeAccessGuard;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.InterviewSessionCreateRequest;
import com.aisw.kkori.session.dto.InterviewSessionCreateResponse;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 면접 세션 생성 오케스트레이션 (docs/requirements/session/interview-session-creation.md).
 *
 * <p><b>트랜잭션 경계</b>: DB 작업(검증·교체·INSERT)만 트랜잭션으로 묶고, LiveKit 왕복(룸
 * 생성·삭제)과 토큰 발급은 <b>커밋 후 트랜잭션 밖</b>에서 수행한다 — 외부 응답을 기다리는
 * 동안 DB 커넥션과 user 행 잠금을 들지 않기 위함이다(직렬화가 실제로 필요한 구간은 순수 DB
 * 작업뿐). 그 대가로 룸 생성·토큰 발급이 실패하면 <b>룸 없는 PENDING 세션이 커밋된 채
 * 남는데</b>, 이는 다음 생성 요청의 PENDING 자동 교체가 정리한다(재시도로 자연 복구).
 *
 * <p>동일 유저의 세션 생성과 이력서 상태 변경(수정·재분석)은 <b>user 행 잠금을 직렬화
 * 지점으로 공유</b>한다 — 이력서 검증(EMBEDDED)과 세션 생성 사이에 삭제·재분석이 끼어들어
 * 무효 이력서를 참조한 세션이 생기는 TOCTOU를 막는다. 이력서 분석 상태는 잠그지 않고 읽는다:
 * 상태를 EMBEDDED에서 되돌리는 유저 경로는 전부 같은 user 잠금으로 직렬화되고, Worker는
 * 상태를 전진만 시키므로 잠금 없는 읽기로 충분하다.
 *
 * <p>LiveKit 벤더 세부는 발급·룸 어댑터({@link SessionTicketIssuer}·{@link SessionRoomManager})에
 * 격리되어 이 서비스는 알지 못한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String ROOM_PREFIX = "room-";
    private static final String IDENTITY_PREFIX = "candidate-";

    private final UserRepository userRepository;
    private final InterviewSessionRepository sessionRepository;
    private final ResumeAccessGuard resumeAccessGuard;
    private final SessionRoomManager roomManager;
    private final SessionTicketIssuer ticketIssuer;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 면접 유형·직무·대상 이력서를 검증하고 PENDING 세션을 생성해 룸·토큰과 함께 반환한다. */
    public InterviewSessionCreateResponse create(Long userId, InterviewSessionCreateRequest request) {
        // [트랜잭션] 검증~레코드 생성 — 커밋과 함께 user 잠금·커넥션이 해제된다
        CreationPlan plan = transactionTemplate.execute(status -> planInTransaction(userId, request));

        // [트랜잭션 밖] LiveKit 왕복·토큰 발급 — 실패해도 커밋된 PENDING은 남으며(룸 미보장),
        // 다음 생성의 자동 교체가 정리한다. 어떤 결과든 교체된 기존 세션의 룸은 정리를 시도한다.
        try {
            roomManager.createRoom(plan.roomName());
            SessionTicket ticket = ticketIssuer.issue(IDENTITY_PREFIX + plan.sessionId(), plan.roomName());
            return new InterviewSessionCreateResponse(
                    plan.sessionId(), ticket.token(), ticket.serverUrl(), plan.roomName());
        } catch (RuntimeException e) {
            // 룸 생성 타임아웃은 "룸이 안 만들어졌다"가 아니라 "응답을 못 받았다"일 수 있고,
            // 토큰 실패 시엔 룸이 실제로 존재한다 — 두 경우 모두 신규 룸을 best-effort로 보상 삭제한다.
            deleteQuietly(plan.roomName());
            throw e;
        } finally {
            // 교체(ABORTED)는 이미 커밋으로 확정됐으므로 성공·실패와 무관하게 기존 룸을 정리한다
            plan.abortedRooms().forEach(this::deleteQuietly);
        }
    }

    /**
     * 트랜잭션 내부: user 잠금 → 이력서 검증 → 기존 세션 판정·교체 → PENDING INSERT.
     * 여기서 던지는 예외는 전부 롤백으로 이어져 아무 흔적도 남기지 않는다(시끄러운 실패).
     */
    private CreationPlan planInTransaction(Long userId, InterviewSessionCreateRequest request) {
        // 1) user 행 잠금 + 활성 재확인 — 탈퇴가 선점했으면 401. 잠금 순서는 user 선행(E1 계약과 무충돌)
        userRepository.findActiveWithLock(userId);

        // 2) 트랜잭션 시각 — 잠금 획득 후 취득 (공통: 시각 처리)
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 3) 이력서 검증 — resumeId가 있으면 유형 무관 동일 적용 (THIRTY_MIN 필수는 요청 검증이 보장)
        if (request.resumeId() != null) {
            resumeAccessGuard.findAuthorized(userId, request.resumeId());
            resumeAccessGuard.requireEmbedded(resumeAccessGuard.statusOf(request.resumeId()));
        }

        // 4) 기존 세션 판정 — 진행 중(ACTIVE 계열)이면 409, PENDING은 ABORTED로 자동 교체.
        //    룸 이름은 벌크 UPDATE(영속성 컨텍스트 clear) 전에 수집한다.
        List<InterviewSession> existing = sessionRepository.findByUserIdAndStatusIn(userId, SessionStatus.NON_TERMINAL);
        if (existing.stream().anyMatch(s -> SessionStatus.IN_PROGRESS.contains(s.getStatus()))) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_IN_PROGRESS);
        }
        List<Long> pendingIds = existing.stream().map(InterviewSession::getId).toList();
        List<String> abortedRooms = existing.stream().map(InterviewSession::getLivekitRoom).toList();
        if (!pendingIds.isEmpty()) {
            // 주의: 이 벌크 UPDATE는 clearAutomatically로 영속성 컨텍스트를 비운다 — 1)에서 잠근 User를
            // 포함해 앞서 조회한 엔티티가 전부 detach되므로, 이 지점 이후 그 엔티티들을 변이하면 안 된다
            // (dirty checking 유실 — cancelPendingPurge의 clearAutomatically 관련 javadoc 참조).
            int aborted = sessionRepository.abortPendingByIds(pendingIds, now);
            if (aborted != pendingIds.size()) {
                // user 잠금 하에서는 도달 불가한 상태 — 잠금을 공유하지 않는 전이 경로(예: 후속 webhook)가
                // 조회~전이 사이에 PENDING을 다른 상태로 바꿨다는 신호다. 계속 진행하면 ACTIVE와 신규
                // PENDING이 공존할 수 있으므로 생성을 중단한다(불변식의 최후 방어선, 전체 롤백).
                log.warn("PENDING 교체 대상과 실제 전이 수 불일치 — 생성 중단 (expected={}, aborted={})",
                        pendingIds.size(), aborted);
                throw new BusinessException(ErrorCode.SESSION_ALREADY_IN_PROGRESS);
            }
        }

        // 5) 신규 세션 저장 — IDENTITY 전략이라 저장 즉시 id가 확보된다 (identity 파생에 필요)
        String roomName = ROOM_PREFIX + UUID.randomUUID();
        InterviewSession session = sessionRepository.save(InterviewSession.pending(
                userId, request.resumeId(), request.interviewType(), request.position(), roomName));
        return new CreationPlan(session.getId(), roomName, abortedRooms);
    }

    /** 룸 삭제는 quiet 계약이지만, 구현 결함으로 던져도 응답·다른 정리에 전파되지 않게 항목별로 격리한다. */
    private void deleteQuietly(String roomName) {
        try {
            roomManager.deleteRoomQuietly(roomName);
        } catch (RuntimeException e) {
            log.warn("룸 삭제 중 예외 — quiet 계약 위반 가능성, 무시하고 진행 (room={})", roomName, e);
        }
    }

    /** 트랜잭션이 커밋한 결과 중 트랜잭션 밖 단계가 필요로 하는 것들. */
    private record CreationPlan(long sessionId, String roomName, List<String> abortedRooms) {
    }
}
