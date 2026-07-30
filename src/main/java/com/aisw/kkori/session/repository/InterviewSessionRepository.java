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
}
