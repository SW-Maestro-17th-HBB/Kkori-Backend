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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 면접 세션 생성 오케스트레이션 (docs/requirements/session/interview-session-creation.md).
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
public class SessionService {

    private static final String ROOM_PREFIX = "room-";
    private static final String IDENTITY_PREFIX = "candidate-";

    private final UserRepository userRepository;
    private final InterviewSessionRepository sessionRepository;
    private final ResumeAccessGuard resumeAccessGuard;
    private final SessionRoomManager roomManager;
    private final SessionTicketIssuer ticketIssuer;
    private final Clock clock;

    public SessionService(UserRepository userRepository, InterviewSessionRepository sessionRepository,
                          ResumeAccessGuard resumeAccessGuard, SessionRoomManager roomManager,
                          SessionTicketIssuer ticketIssuer, Clock clock) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.resumeAccessGuard = resumeAccessGuard;
        this.roomManager = roomManager;
        this.ticketIssuer = ticketIssuer;
        this.clock = clock;
    }

    /** 면접 유형·직무·대상 이력서를 검증하고 PENDING 세션을 생성해 룸·토큰과 함께 반환한다. */
    @Transactional
    public InterviewSessionCreateResponse create(Long userId, InterviewSessionCreateRequest request) {
        // 1) user 행 잠금 + 활성 재확인 — 탈퇴가 선점했으면 401. 잠금 순서는 user 선행(E1 계약과 무충돌)
        userRepository.findWithLockById(userId)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

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
            int aborted = sessionRepository.abortPendingByIds(pendingIds, now);
            if (aborted != pendingIds.size()) {
                // user 잠금 하에서는 일어날 수 없는 상태 — 방어적 감지만 하고 진행한다
                log.warn("PENDING 교체 대상과 실제 전이 수 불일치 (expected={}, aborted={})", pendingIds.size(), aborted);
            }
        }

        // 5) 신규 세션 저장 — IDENTITY 전략이라 저장 즉시 id가 확보된다 (identity 파생에 필요)
        String roomName = ROOM_PREFIX + UUID.randomUUID();
        InterviewSession session = sessionRepository.save(InterviewSession.pending(
                userId, request.resumeId(), request.interviewType(), request.position(), roomName));

        // 6) 정리·보상 동기화 등록 — 반드시 createRoom 전에. 타임아웃은 룸이 실제로 만들어졌을 수
        //    있으므로(응답만 유실) 룸 생성 실패 경로에서도 보상 삭제가 시도되어야 한다.
        TransactionSynchronizationManager.registerSynchronization(
                new SessionRoomCleanup(roomManager, roomName, abortedRooms));

        // 7) 룸 명시 생성 — 실패 시 S002로 전체 롤백 (기존 세션 교체도 함께 되돌아간다)
        roomManager.createRoom(roomName);

        // 8) 토큰 발급 — 세션 파생 신원(candidate-{sessionId}, 저장 없음), 실패 시 S001로 전체 롤백
        SessionTicket ticket = ticketIssuer.issue(IDENTITY_PREFIX + session.getId(), roomName);
        return new InterviewSessionCreateResponse(session.getId(), ticket.token(), ticket.serverUrl(), roomName);
    }
}
