package com.aisw.kkori.session.repository;

import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    List<InterviewSession> findByUserIdAndStatusIn(Long userId, Collection<SessionStatus> statuses);

    /**
     * PENDING 세션을 ABORTED로 교체 정리한다 (조건부 벌크 UPDATE).
     *
     * <p>status 술어가 "이미 terminal이면 no-op" 가드다. 벌크 쿼리에는 auditing이 적용되지
     * 않으므로 {@code updatedAt}을 명시적으로 갱신한다(deletion_log 상태 전이와 동일 방침).
     * 호출 트랜잭션은 user 행 잠금으로 직렬화되어 있어야 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = com.aisw.kkori.session.domain.SessionStatus.ABORTED,
                s.endedAt = :now,
                s.updatedAt = :now
            where s.id in :ids
              and s.status = com.aisw.kkori.session.domain.SessionStatus.PENDING
            """)
    int abortPendingByIds(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /** "진행 중 면접에서 사용 중인 이력서" 판정 — RESUME_IN_USE 검사의 원천. */
    boolean existsByResumeIdAndStatusIn(Long resumeId, Collection<SessionStatus> statuses);

    /** 커밋 후 승계(superseded) 재확인 — 디스패치 뒤에도 이 세션이 여전히 해당 상태인지 판정. */
    boolean existsByIdAndStatus(Long id, SessionStatus status);

    /** webhook의 세션 역추적 — 룸 이름은 UNIQUE(ux_interview_session_livekit_room). */
    Optional<InterviewSession> findByLivekitRoom(String livekitRoom);

    // ── 이하 전이·스위퍼 쿼리 (PRD interview-session-completion.md) ──
    // 모든 전이는 상태 술어를 단 조건부 벌크 UPDATE로 원자적·멱등이다(중복·역순 webhook,
    // terminal no-op 가드). 벌크 쿼리에는 auditing이 적용되지 않아 updatedAt을 명시 갱신하며,
    // 호출 트랜잭션은 user 행 잠금(활성 재확인 없는 findWithLockById)으로 직렬화되어 있어야 한다.

    /**
     * PENDING → ACTIVE 전환. webhook(participant_joined)은 {@code startedAt=now}로, stale
     * PENDING의 관측 기반 복원은 LiveKit 참가자 입장 시각으로 호출한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = com.aisw.kkori.session.domain.SessionStatus.ACTIVE,
                s.startedAt = :startedAt,
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.PENDING
            """)
    int activate(@Param("id") Long id, @Param("startedAt") Instant startedAt, @Param("now") Instant now);

    /** terminal 확정 — {@code to}는 ENDED/ABORTED만 허용된다(호출측 계약). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = :to,
                s.endedAt = :now,
                s.updatedAt = :now
            where s.id = :id
              and s.status in :from
            """)
    int finishFrom(@Param("id") Long id, @Param("from") Collection<SessionStatus> from,
                   @Param("to") SessionStatus to, @Param("now") Instant now);

    /**
     * stale ACTIVE 회수 전용 terminal 확정 — {@code endRequestedAt}이 있는 세션은 fallback이
     * 전담하므로 술어에서 제외한다(후보 조회~전이 사이의 /end 경합에도 우선순위가 유지된다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = :to,
                s.endedAt = :now,
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.ACTIVE
              and s.endRequestedAt is null
            """)
    int finishStaleActive(@Param("id") Long id, @Param("to") SessionStatus to, @Param("now") Instant now);

    /** 판별 ③ — AGENT_LOST 전이(유예 앵커 기록). 대상은 PENDING·ACTIVE·INTERRUPTED(HBB1-308).
     * disconnectedAt은 건드리지 않는다 — INTERRUPTED발 전이의 재연결 deadline 앵커 보존(계약). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = com.aisw.kkori.session.domain.SessionStatus.AGENT_LOST,
                s.agentLostAt = :now,
                s.updatedAt = :now
            where s.id = :id
              and s.status in (com.aisw.kkori.session.domain.SessionStatus.PENDING,
                               com.aisw.kkori.session.domain.SessionStatus.ACTIVE,
                               com.aisw.kkori.session.domain.SessionStatus.INTERRUPTED)
            """)
    int markAgentLost(@Param("id") Long id, @Param("now") Instant now);

    // ── 재연결 전이 (PRD interview-session-reconnection.md 기능 1) ──

    /** candidate 이탈 — ACTIVE → INTERRUPTED (`disconnected_at` = 현재 episode 이탈 관측 시각). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = com.aisw.kkori.session.domain.SessionStatus.INTERRUPTED,
                s.disconnectedAt = :now,
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.ACTIVE
            """)
    int interrupt(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 복귀 — INTERRUPTED → ACTIVE, `disconnected_at` 초기화(다음 이탈이 새 episode 창을 연다).
     * `started_at`·`end_requested_at`은 불변(최초 보존 — 재앵커·fallback 창 연장 없음).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = com.aisw.kkori.session.domain.SessionStatus.ACTIVE,
                s.disconnectedAt = null,
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.INTERRUPTED
            """)
    int resumeFromInterrupted(@Param("id") Long id, @Param("now") Instant now);

    /** AGENT_LOST 중 candidate 이탈 — 이탈 관측 시각만 기록(null일 때만 — 창 연장 금지). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.disconnectedAt = :now,
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.AGENT_LOST
              and s.disconnectedAt is null
            """)
    int recordDisconnectedIfAbsent(@Param("id") Long id, @Param("now") Instant now);

    /** INTERRUPTED 유예 만료 전용 terminal 확정 — end_requested_at 있는 세션은 fallback 전담(우선순위 분리). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = :to,
                s.endedAt = :now,
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.INTERRUPTED
              and s.endRequestedAt is null
            """)
    int finishInterruptedGrace(@Param("id") Long id, @Param("to") SessionStatus to, @Param("now") Instant now);

    /** INTERRUPTED 유예 만료 후보 — end_requested_at 있는 세션 제외(fallback 전담). */
    List<InterviewSession> findByStatusAndEndRequestedAtIsNullAndDisconnectedAtLessThanEqual(
            SessionStatus status, Instant cutoff);

    // ── 재디스패치 (PRD interview-session-reconnection.md 기능 3) ──

    /**
     * 재디스패치 CAS — at-most-once 권한 획득. AGENT 사전 확인에서 부재가 확인된 뒤에만
     * 시도한다(관측 기반 복원은 이 마커를 소진하지 않는다 — 복원된 에이전트의 후속 소실이
     * 온전한 재디스패치 기회를 가진다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.redispatchedAt = :now,
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.AGENT_LOST
              and s.redispatchedAt is null
            """)
    int claimRedispatch(@Param("id") Long id, @Param("now") Instant now);

    /**
     * AGENT_LOST → ACTIVE 복귀 (joined(agent) 대조·사전 확인 공용 — candidate 재실).
     * started_at은 보존하되 null(PENDING발 AGENT_LOST)이면 현재 시각을 기록한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = com.aisw.kkori.session.domain.SessionStatus.ACTIVE,
                s.disconnectedAt = null,
                s.startedAt = coalesce(s.startedAt, :now),
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.AGENT_LOST
            """)
    int resumeAgentLostToActive(@Param("id") Long id, @Param("now") Instant now);

    /**
     * AGENT_LOST → INTERRUPTED (candidate 부재 — 잔여 재연결 창으로). disconnected_at은
     * 보존(first-wins — 재연결 deadline·기발급 재입장 토큰의 앵커)하되 null이면 지금 창을 연다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.status = com.aisw.kkori.session.domain.SessionStatus.INTERRUPTED,
                s.disconnectedAt = coalesce(s.disconnectedAt, :now),
                s.startedAt = coalesce(s.startedAt, :now),
                s.updatedAt = :now
            where s.id = :id
              and s.status = com.aisw.kkori.session.domain.SessionStatus.AGENT_LOST
            """)
    int resumeAgentLostToInterrupted(@Param("id") Long id, @Param("now") Instant now);

    /** 종료 요청 시각 기록 — first-wins(이미 있으면 no-op)라 중복 /end가 fallback 창을 연장하지 않는다.
     * INTERRUPTED 포함(HBB1-308 — /end의 ACTIVE 동일 취급). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.endRequestedAt = :now,
                s.updatedAt = :now
            where s.id = :id
              and s.status in (com.aisw.kkori.session.domain.SessionStatus.ACTIVE,
                               com.aisw.kkori.session.domain.SessionStatus.INTERRUPTED)
              and s.endRequestedAt is null
            """)
    int recordEndRequested(@Param("id") Long id, @Param("now") Instant now);

    /** fallback 후보 — /end 수리 후 room_finished가 오지 않은 ACTIVE·INTERRUPTED(HBB1-308 확장). */
    List<InterviewSession> findByStatusInAndEndRequestedAtLessThanEqual(
            Collection<SessionStatus> statuses, Instant cutoff);

    /** 유예 만료 후보. */
    List<InterviewSession> findByStatusAndAgentLostAtLessThanEqual(SessionStatus status, Instant cutoff);

    /** stale ACTIVE 후보 — end_requested_at 있는 세션은 fallback 전담(PRD 우선순위 분리). */
    List<InterviewSession> findByStatusAndEndRequestedAtIsNullAndStartedAtLessThanEqual(
            SessionStatus status, Instant cutoff);

    /** stale PENDING 후보. */
    List<InterviewSession> findByStatusAndCreatedAtLessThanEqual(SessionStatus status, Instant cutoff);

    // ── 녹음·음성 분석 (PRD interview-recording.md) ──
    // 녹음 컬럼은 상태 머신과 독립이다(terminal 세션에도 기록) — 상태 술어 없이 동작하며,
    // user 행 잠금도 선행하지 않는다(전이 경로와 다투는 컬럼이 없고, 중복 webhook 경합은
    // recording_object_key IS NULL 술어가 원자적으로 거른다).

    /** egress_ended webhook의 세션 역매핑 — egress_id는 시작 성공 시에만 기록된다. */
    Optional<InterviewSession> findByEgressId(String egressId);

    /** 녹음 시작 성공 후 egress id 기록. 상태 무관 — 승계 경합으로 ABORTED된 세션에 남아도 무해하다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.egressId = :egressId,
                s.updatedAt = :now
            where s.id = :id
            """)
    int updateEgressId(@Param("id") Long id, @Param("egressId") String egressId, @Param("now") Instant now);

    /** 녹음 업로드 완료 기록 — recording_object_key IS NULL 술어가 멱등 가드(중복 webhook no-op). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
            set s.recordingBucket = :bucket,
                s.recordingObjectKey = :objectKey,
                s.updatedAt = :now
            where s.id = :id
              and s.recordingObjectKey is null
            """)
    int recordRecordingResult(@Param("id") Long id, @Param("bucket") String bucket,
                              @Param("objectKey") String objectKey, @Param("now") Instant now);
}
