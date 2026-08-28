package com.aisw.kkori.session.repositoryservice;

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
 * 각 위임 메서드의 전이 조건·멱등 계약은 {@link InterviewSessionRepository}의 javadoc 참조.
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

    public Optional<InterviewSession> findById(Long id) {
        return sessionRepository.findById(id);
    }

    /** {@link InterviewSessionRepository#findByLivekitRoom} 위임. */
    public Optional<InterviewSession> findByLivekitRoom(String livekitRoom) {
        return sessionRepository.findByLivekitRoom(livekitRoom);
    }

    /** {@link InterviewSessionRepository#findByEgressId} 위임. */
    public Optional<InterviewSession> findByEgressId(String egressId) {
        return sessionRepository.findByEgressId(egressId);
    }

    public List<InterviewSession> findByUserIdAndStatusIn(Long userId, Collection<SessionStatus> statuses) {
        return sessionRepository.findByUserIdAndStatusIn(userId, statuses);
    }

    /** {@link InterviewSessionRepository#existsByIdAndStatus} 위임. */
    public boolean existsByIdAndStatus(Long id, SessionStatus status) {
        return sessionRepository.existsByIdAndStatus(id, status);
    }

    /** {@link InterviewSessionRepository#existsByResumeIdAndStatusIn} 위임. */
    public boolean existsByResumeIdAndStatusIn(Long resumeId, Collection<SessionStatus> statuses) {
        return sessionRepository.existsByResumeIdAndStatusIn(resumeId, statuses);
    }

    // ── 상태 전이 (조건부 벌크 UPDATE — 원자적·멱등, 계약은 repository javadoc) ──

    /**
     * {@link InterviewSessionRepository#abortPendingByIds} 위임.
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

    public List<InterviewSession> findByStatusInAndEndRequestedAtLessThanEqual(
            Collection<SessionStatus> statuses, Instant cutoff) {
        return sessionRepository.findByStatusInAndEndRequestedAtLessThanEqual(statuses, cutoff);
    }

    public List<InterviewSession> findByStatusAndEndRequestedAtIsNullAndDisconnectedAtLessThanEqual(
            SessionStatus status, Instant cutoff) {
        return sessionRepository.findByStatusAndEndRequestedAtIsNullAndDisconnectedAtLessThanEqual(status, cutoff);
    }

    public List<InterviewSession> findByStatusAndAgentLostAtLessThanEqual(SessionStatus status, Instant cutoff) {
        return sessionRepository.findByStatusAndAgentLostAtLessThanEqual(status, cutoff);
    }

    public List<InterviewSession> findByStatusAndEndRequestedAtIsNullAndStartedAtLessThanEqual(
            SessionStatus status, Instant cutoff) {
        return sessionRepository.findByStatusAndEndRequestedAtIsNullAndStartedAtLessThanEqual(status, cutoff);
    }

    public List<InterviewSession> findByStatusAndCreatedAtLessThanEqual(SessionStatus status, Instant cutoff) {
        return sessionRepository.findByStatusAndCreatedAtLessThanEqual(status, cutoff);
    }

    // ── 증거 읽기 (네이티브·Redis — 호출부는 의도적으로 트랜잭션 밖에서 부른다) ──

    /** {@link InterviewTranscriptReader#exists} 위임 — 대본 행 존재 판별. */
    public boolean transcriptExists(long sessionId) {
        return transcriptReader.exists(sessionId);
    }

    /** {@link TerminationMarkerReader#read} 위임 — Redis 종료 표식 읽기. */
    public Optional<TerminationMarker> readTerminationMarker(long sessionId) {
        return markerReader.read(sessionId);
    }
}
