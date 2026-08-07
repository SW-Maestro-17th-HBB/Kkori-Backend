package com.aisw.kkori.session.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.StructuredData;
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
 * <p><b>트랜잭션 경계</b>: LiveKit 룸을 <b>트랜잭션 전에 선생성</b>하고, DB 작업(검증·교체·INSERT·
 * 디스패치 metadata 조립)만 트랜잭션으로 묶은 뒤, 토큰 발급(로컬 서명)·에이전트 디스패치·기존 룸
 * 정리는 커밋 후에 한다 — 외부 응답을 기다리는 동안 DB 커넥션과 user 행 잠금을 들지 않기
 * 위함이다(직렬화가 필요한 구간은 순수 DB 작업뿐). 선생성 덕에 <b>"세션 행이 존재하면 룸은 이미
 * 존재한다"는 불변식</b>이 성립해, 동시 생성이 이 세션을 교체(ABORTED)하며 수행하는 룸 삭제가
 * 항상 실효적이다(생성 중 세션을 교체가 앞질러 삭제가 헛도는 경합 차단). 실패 모델: 룸 생성
 * 실패(S002)는 DB 접촉 전이라 무흔적, 트랜잭션 실패는 롤백 + 선생성 룸 보상 삭제, 커밋 후
 * 실패(토큰 서명 S001·디스패치 S004)만 커밋된 PENDING이 잔존하며(룸은 보상 삭제) 다음 생성의
 * 자동 교체가 수렴시킨다. 대가로 검증 거부(403/404/409)될 요청도 룸을 선생성·즉시 삭제하는
 * 왕복 낭비가 있으나 빈도가 낮아 수용한다(PRD 기능 2 — agent-dispatch.md가 개정).
 *
 * <p>동일 유저의 세션 생성과 이력서 상태 변경(수정·재분석)은 <b>user 행 잠금을 직렬화
 * 지점으로 공유</b>한다 — 이력서 검증(EMBEDDED)과 세션 생성 사이에 삭제·재분석이 끼어들어
 * 무효 이력서를 참조한 세션이 생기는 TOCTOU를 막는다. 이력서 분석 상태는 잠그지 않고 읽는다:
 * 상태를 EMBEDDED에서 되돌리는 유저 경로는 전부 같은 user 잠금으로 직렬화되고, Worker는
 * 상태를 전진만 시키므로 잠금 없는 읽기로 충분하다.
 *
 * <p>LiveKit 벤더 세부는 발급·룸·디스패치 어댑터({@link SessionTicketIssuer}·{@link SessionRoomManager}·
 * {@link SessionAgentDispatcher})에 격리되어 이 서비스는 알지 못한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String ROOM_PREFIX = "room-";

    private final UserRepository userRepository;
    private final InterviewSessionRepository sessionRepository;
    private final ResumeAccessGuard resumeAccessGuard;
    private final SessionRoomManager roomManager;
    private final SessionTicketIssuer ticketIssuer;
    private final DispatchMetadataAssembler metadataAssembler;
    private final SessionAgentDispatcher agentDispatcher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 면접 유형·직무·대상 이력서를 검증하고 PENDING 세션을 생성해 룸·토큰과 함께 반환한다. */
    public InterviewSessionCreateResponse create(Long userId, InterviewSessionCreateRequest request) {
        // [트랜잭션 전] 룸 선생성 — 실패(S002)는 DB 접촉 전이라 아무 흔적도 남기지 않는다.
        // 세션 행보다 룸이 먼저 존재해야, 동시 생성의 교체가 수행하는 룸 삭제가 항상 실효적이다.
        String roomName = ROOM_PREFIX + UUID.randomUUID();
        try {
            roomManager.createRoom(roomName);
        } catch (RuntimeException e) {
            // 타임아웃은 룸이 실제로 만들어졌을 수 있다(응답만 유실) — 시도한 이름으로 보상 삭제한다
            deleteQuietly(roomName);
            throw e;
        }

        // [트랜잭션] 검증~레코드 생성 — 커밋과 함께 user 잠금·커넥션이 해제된다.
        // 실패(검증 거부·경합 중단)는 전체 롤백이므로 선생성한 룸을 보상 삭제한다.
        CreationPlan plan;
        try {
            plan = transactionTemplate.execute(status -> planInTransaction(userId, request, roomName));
        } catch (RuntimeException e) {
            deleteQuietly(roomName);
            throw e;
        }

        // [커밋 후] 토큰 발급(로컬 서명) → 에이전트 디스패치(LiveKit 왕복) — 토큰을 먼저 확정해,
        // 유저가 입장할 수 없는 룸에 에이전트만 입장시키는 상태를 만들지 않는다. 실패(S001/S004)해도
        // 커밋된 PENDING은 남으며 다음 생성의 자동 교체가 수렴시킨다. 어떤 결과든 교체된 기존
        // 세션의 룸은 정리를 시도한다.
        try {
            SessionTicket ticket = ticketIssuer.issue(CandidateIdentity.of(plan.sessionId()), roomName);
            agentDispatcher.dispatch(roomName, plan.dispatchMetadata());

            // 승계(superseded) 재확인 — user 잠금은 트랜잭션 구간만 직렬화하므로, 커밋과 디스패치
            // 사이에 동시 생성이 이 세션을 교체(ABORTED)하고 룸을 삭제했을 수 있다. createDispatch는
            // 미존재 룸을 자동 생성하므로(실측 확인) 방금 호출이 에이전트 있는 좀비 룸을 되살렸을 수
            // 있다 — 교체가 감지되면 룸을 보상 삭제(재생성 룸·agent job 함께 소멸)하고 409로 끝낸다.
            // 교체가 이 재확인 뒤에 오는 경우는 정상 교체 흐름과 같다(교체측 룸 삭제가 job까지 정리).
            if (!sessionRepository.existsByIdAndStatus(plan.sessionId(), SessionStatus.PENDING)) {
                throw new BusinessException(ErrorCode.SESSION_SUPERSEDED);
            }
            return new InterviewSessionCreateResponse(
                    plan.sessionId(), ticket.token(), ticket.serverUrl(), roomName);
        } catch (RuntimeException e) {
            deleteQuietly(roomName);
            throw e;
        } finally {
            // 교체(ABORTED)는 커밋으로 확정됐고, 선생성 불변식 덕에 그 룸들은 반드시 실존한다
            plan.abortedRooms().forEach(this::deleteQuietly);
        }
    }

    /**
     * 트랜잭션 내부: user 잠금 → 이력서 검증 → 기존 세션 판정·교체 → PENDING INSERT.
     * 여기서 던지는 예외는 전부 롤백으로 이어져 DB에는 아무 흔적도 남기지 않는다(선생성 룸은
     * 호출측이 보상 삭제).
     */
    private CreationPlan planInTransaction(Long userId, InterviewSessionCreateRequest request, String roomName) {
        // 1) user 행 잠금 + 활성 재확인 — 탈퇴가 선점했으면 401. 잠금 순서는 user 선행(E1 계약과 무충돌)
        userRepository.findActiveWithLock(userId);

        // 2) 트랜잭션 시각 — 잠금 획득 후 취득 (공통: 시각 처리)
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 3) 이력서 검증 — resumeId가 있으면 유형 무관 동일 적용 (THIRTY_MIN 필수는 요청 검증이 보장).
        //    디스패치 조립 입력(structured_data)도 여기서 확보한다 — 4)의 벌크 UPDATE가 영속성
        //    컨텍스트를 비우기 전이자, 검증과 같은 잠금·스냅샷이라 조립 입력의 일관성이 보장된다.
        StructuredData structuredData = null;
        if (request.resumeId() != null) {
            structuredData = resumeAccessGuard.findAuthorized(userId, request.resumeId()).getStructuredData();
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

        // 5) 신규 세션 저장 — IDENTITY 전략이라 저장 즉시 id가 확보된다 (identity·metadata 파생에 필요).
        //    디스패치 metadata는 트랜잭션 안에서 최종 JSON까지 확정한다 — 직렬화 실패(C001)가
        //    전체 롤백 + 선생성 룸 보상 삭제로 수렴하고, 커밋 후 단계는 실패 요인이 LiveKit 왕복뿐이게 된다.
        InterviewSession session = sessionRepository.save(InterviewSession.pending(
                userId, request.resumeId(), request.interviewType(), request.position(), roomName));
        String dispatchMetadata = metadataAssembler.assemble(
                session.getId(), request.interviewType(), request.position(), structuredData);
        return new CreationPlan(session.getId(), abortedRooms, dispatchMetadata);
    }

    /** 룸 삭제는 quiet 계약이지만, 구현 결함으로 던져도 응답·다른 정리에 전파되지 않게 항목별로 격리한다. */
    private void deleteQuietly(String roomName) {
        try {
            roomManager.deleteRoomQuietly(roomName);
        } catch (RuntimeException e) {
            log.warn("룸 삭제 중 예외 — quiet 계약 위반 가능성, 무시하고 진행 (room={})", roomName, e);
        }
    }

    /** 트랜잭션이 커밋한 결과 중 커밋 후 단계가 필요로 하는 것들. */
    private record CreationPlan(long sessionId, List<String> abortedRooms, String dispatchMetadata) {
    }
}
