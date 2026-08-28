package com.aisw.kkori.session.repositoryservice;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.TerminationMarker;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * session 도메인 영속성 접근 계층. service·타 도메인은 raw repository 대신 이 계층을 거친다
 * (CLAUDE.md 패키지 구조 규칙). 트랜잭션은 소유하지 않는다 — 전이 벌크 UPDATE는 반드시
 * 호출자의 user 행 잠금 트랜잭션 안에서, 증거 읽기({@link #transcriptExists},
 * {@link #readTerminationMarker})는 의도적으로 잠금 트랜잭션 밖에서 호출된다(호출부 계약).
 * 각 메서드의 전이 조건·멱등 계약은 {@link InterviewSessionRepository}의 javadoc 참조.
 */
@Service
@RequiredArgsConstructor
public class SessionRepositoryService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewTranscriptReader transcriptReader;
    private final TerminationMarkerReader markerReader;

    // ── 조회·저장 ──

    public InterviewSession save(InterviewSession session) {
        return sessionRepository.save(session);
    }

    /** 존재(404) → 소유(403) 검증 — 소유자 API(종료·재입장)의 진입 검증. */
    public InterviewSession getOwned(Long userId, Long sessionId) {
        InterviewSession session = getById(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.SESSION_FORBIDDEN);
        }
        return session;
    }

    /** 존재 검증 조회 — 부재는 404. 잠금 획득 후 최신 상태를 다시 읽는 경로에도 쓴다. */
    public InterviewSession getById(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    public Optional<InterviewSession> findByLivekitRoom(String livekitRoom) {
        return sessionRepository.findByLivekitRoom(livekitRoom);
    }

    public Optional<InterviewSession> findByEgressId(String egressId) {
        return sessionRepository.findByEgressId(egressId);
    }

    /** 유저의 종결되지 않은(non-terminal) 세션 전부 — 생성 시 기존 세션 판정용. */
    public List<InterviewSession> findNonTerminalByUserId(Long userId) {
        return sessionRepository.findByUserIdAndStatusIn(userId, SessionStatus.NON_TERMINAL);
    }

    /** 세션이 아직 해당 상태인지 재확인 — 커밋 후 승계·재디스패치 직전 검사용. */
    public boolean isInStatus(Long id, SessionStatus status) {
        return sessionRepository.existsByIdAndStatus(id, status);
    }

    /** 이력서를 참조하는 종결되지 않은 세션 존재 여부 — "사용 중 이력서" 판정의 원천. */
    public boolean hasNonTerminalByResumeId(Long resumeId) {
        return sessionRepository.existsByResumeIdAndStatusIn(resumeId, SessionStatus.NON_TERMINAL);
    }

    // ── 상태 전이 (조건부 벌크 UPDATE — 원자적·멱등, 계약은 repository javadoc) ──

    /**
     * 주의: {@code clearAutomatically}가 영속성 컨텍스트를 비워, 호출 트랜잭션이 직전에
     * 잠근 User 엔티티가 detach된다 — 이후 그 엔티티의 dirty checking에 의존하지 말 것.
     */
    public int abortPendingByIds(Collection<Long> ids, Instant now) {
        return sessionRepository.abortPendingByIds(ids, now);
    }

    public int activate(Long id, Instant startedAt, Instant now) {
        return sessionRepository.activate(id, startedAt, now);
    }

    public int finishFrom(Long id, Collection<SessionStatus> from, SessionStatus to, Instant now) {
        return sessionRepository.finishFrom(id, from, to, now);
    }

    public int finishStaleActive(Long id, SessionStatus to, Instant now) {
        return sessionRepository.finishStaleActive(id, to, now);
    }

    public int finishInterruptedGrace(Long id, SessionStatus to, Instant now) {
        return sessionRepository.finishInterruptedGrace(id, to, now);
    }

    public int markAgentLost(Long id, Instant now) {
        return sessionRepository.markAgentLost(id, now);
    }

    public int interrupt(Long id, Instant now) {
        return sessionRepository.interrupt(id, now);
    }

    public int resumeFromInterrupted(Long id, Instant now) {
        return sessionRepository.resumeFromInterrupted(id, now);
    }

    public int recordDisconnectedIfAbsent(Long id, Instant now) {
        return sessionRepository.recordDisconnectedIfAbsent(id, now);
    }

    public int claimRedispatch(Long id, Instant now) {
        return sessionRepository.claimRedispatch(id, now);
    }

    public int resumeAgentLostToActive(Long id, Instant now) {
        return sessionRepository.resumeAgentLostToActive(id, now);
    }

    public int resumeAgentLostToInterrupted(Long id, Instant now) {
        return sessionRepository.resumeAgentLostToInterrupted(id, now);
    }

    public int recordEndRequested(Long id, Instant now) {
        return sessionRepository.recordEndRequested(id, now);
    }

    public int updateEgressId(Long id, String egressId, Instant now) {
        return sessionRepository.updateEgressId(id, egressId, now);
    }

    public int recordRecordingResult(Long id, String bucket, String objectKey, Instant now) {
        return sessionRepository.recordRecordingResult(id, bucket, objectKey, now);
    }

    // ── 스위퍼 후보 조회 ──

    /** /end 수리 후 room_finished가 오지 않은 fallback 후보 — 종료 요청 시각이 컷오프를 지난 세션. */
    public List<InterviewSession> findEndRequestedFallbackCandidates(
            Collection<SessionStatus> statuses, Instant cutoff) {
        return sessionRepository.findByStatusInAndEndRequestedAtLessThanEqual(statuses, cutoff);
    }

    /** 재연결 유예 만료 후보 — 이탈 시각이 컷오프를 지난 INTERRUPTED(종료 요청 있는 세션은 fallback 전담). */
    public List<InterviewSession> findInterruptedGraceExpired(Instant cutoff) {
        return sessionRepository.findByStatusAndEndRequestedAtIsNullAndDisconnectedAtLessThanEqual(
                SessionStatus.INTERRUPTED, cutoff);
    }

    /** 에이전트 소실 유예 만료 후보 — 소실 관측 시각이 컷오프를 지난 AGENT_LOST. */
    public List<InterviewSession> findAgentLostGraceExpired(Instant cutoff) {
        return sessionRepository.findByStatusAndAgentLostAtLessThanEqual(SessionStatus.AGENT_LOST, cutoff);
    }

    /** stale ACTIVE 후보 — 시작 시각이 컷오프를 지난 ACTIVE(종료 요청 있는 세션은 fallback 전담). */
    public List<InterviewSession> findStaleActive(Instant cutoff) {
        return sessionRepository.findByStatusAndEndRequestedAtIsNullAndStartedAtLessThanEqual(
                SessionStatus.ACTIVE, cutoff);
    }

    /** stale PENDING 후보 — 생성 시각이 컷오프를 지난 PENDING. */
    public List<InterviewSession> findStalePending(Instant cutoff) {
        return sessionRepository.findByStatusAndCreatedAtLessThanEqual(SessionStatus.PENDING, cutoff);
    }

    // ── 증거 읽기 (네이티브·Redis — 호출부는 의도적으로 트랜잭션 밖에서 부른다) ──

    public boolean transcriptExists(long sessionId) {
        return transcriptReader.exists(sessionId);
    }

    public Optional<TerminationMarker> readTerminationMarker(long sessionId) {
        return markerReader.read(sessionId);
    }
}
