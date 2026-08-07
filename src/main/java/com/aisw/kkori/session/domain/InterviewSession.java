package com.aisw.kkori.session.domain;

import com.aisw.kkori.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 면접 세션 (ERD INTERVIEW_SESSION — docs/erd.md).
 *
 * <p>{@code Resume}와 달리 {@code @SQLRestriction}을 두지 않는다 — 본 스토리에서
 * {@code deleted_at}은 쓰이지 않고(E1 파기 연계는 후속 스토리), 조회 경로가 상태
 * 술어로 대상을 한정하므로 User와 같은 수동 확인 방식을 따른다.
 *
 * <p>상태 전이 메서드를 두지 않는다 — 본 스토리의 유일한 전이(PENDING → ABORTED 자동
 * 교체)는 리포지토리의 조건부 벌크 UPDATE가 수행하고, 나머지 전이는 후속 스토리 소관.
 */
@Entity
@Table(name = "interview_session",
        indexes = @Index(name = "ix_interview_session_user_id_status", columnList = "user_id, status"),
        uniqueConstraints = @UniqueConstraint(name = "ux_interview_session_livekit_room", columnNames = "livekit_room"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유 유저 (users.id). 도메인 간 결합을 낮추기 위해 연관관계 대신 id만 보관한다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 대상 이력서 (resumes.id). THIRTY_MIN은 필수·FIVE_MIN은 선택이며 필수 여부는
     * 애플리케이션 검증이 담당한다(스키마는 nullable — 미제출 FIVE_MIN은 NULL).
     */
    @Column(name = "resume_id")
    private Long resumeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_type", nullable = false, length = 16)
    private InterviewType interviewType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Position position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionStatus status;

    /** 세션↔룸 매핑. 후속 webhook 스토리가 룸 식별자로 세션을 역추적하는 조회 키. */
    @Column(name = "livekit_room", nullable = false)
    private String livekitRoom;

    /** ACTIVE 전환 시각 — 후속 스토리 사용. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** terminal 전환 시각 — 본 스토리는 PENDING 자동 교체(ABORTED) 시 벌크 UPDATE가 기록. */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** INTERRUPTED 전환 시각 — 후속 스토리 사용. */
    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    /**
     * 최초 종료 요청(/end) 시각 — fallback 스위퍼의 만료 앵커(first-wins, 중복 /end로 갱신되지
     * 않아 fallback 창이 연장되지 않는다). 값이 있는 ACTIVE 세션은 fallback이 전담하고 stale
     * 회수 대상에서 빠진다(PRD 기능 2·3 우선순위 분리).
     */
    @Column(name = "end_requested_at")
    private Instant endRequestedAt;

    /** AGENT_LOST 전이 시각 — 유예 스위퍼의 만료 앵커. */
    @Column(name = "agent_lost_at")
    private Instant agentLostAt;

    /**
     * 재디스패치 CAS 마커(HBB1-308) — "실제 dispatch 생성 시도 권한의 소진"이며 세션 생애
     * 최대 1회(at-most-once). 값의 존재는 CAS 도달만 뜻한다(생성 성공·실패·미실행은 로그
     * correlation으로 구분). 관측 기반 복원(사전 확인)은 dispatch를 만들지 않으므로 이 마커를
     * 소진하지 않는다.
     */
    @Column(name = "redispatched_at")
    private Instant redispatchedAt;

    private InterviewSession(Long userId, Long resumeId, InterviewType interviewType,
                             Position position, String livekitRoom) {
        this.userId = userId;
        this.resumeId = resumeId;
        this.interviewType = interviewType;
        this.position = position;
        this.status = SessionStatus.PENDING;
        this.livekitRoom = livekitRoom;
    }

    public static InterviewSession pending(Long userId, Long resumeId, InterviewType interviewType,
                                           Position position, String livekitRoom) {
        return new InterviewSession(userId, resumeId, interviewType, position, livekitRoom);
    }
}
