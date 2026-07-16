package com.aisw.kkori.resume.repository;

import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ResumeAnalysisStatusRepository extends JpaRepository<ResumeAnalysisStatus, Long> {

    Optional<ResumeAnalysisStatus> findByResumeId(Long resumeId);

    /**
     * 상태 row를 잠그고 읽는다(SELECT ... FOR UPDATE) — 상태 확인 후 행동하는 트랜잭션(수정·재분석)끼리
     * 직렬화해 낡은 판정으로 통과하는 경합을 막는다. 락은 이름이 아니라 {@code @Lock}이 건다 —
     * ForUpdate는 서술일 뿐이므로 애너테이션을 지우면 이름만 남고 락은 조용히 사라진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ResumeAnalysisStatus> findForUpdateByResumeId(Long resumeId);
}
