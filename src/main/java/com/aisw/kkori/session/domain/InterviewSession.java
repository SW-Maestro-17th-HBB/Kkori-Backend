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
