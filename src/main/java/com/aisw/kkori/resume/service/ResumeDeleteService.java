package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 이력서 삭제 (docs/requirements/resume/resume.md §5).
 *
 * <p>MVP는 soft delete만 수행한다 — S3 원본·구조화 데이터·청크·임베딩의 물리 삭제는
 * 후속 배치 소관이며, 같은 objectKey를 공유하는 활성 이력서 참조 확인도 그 배치가 담당한다.
 * soft delete 즉시 @SQLRestriction으로 목록·상세 조회에서 사라진다.
 *
 * <p>삭제는 <b>user 행 잠금을 먼저 획득</b>해 면접 세션 생성과 직렬화한다
 * (interview-session-creation.md — 이력서 사용 중 차단): 수정·재분석과 동일 관례.
 */
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {

    private final ResumeRepositoryService resumeRepositoryService;
    private final UserRepositoryService userRepositoryService;
    private final ResumeUsageChecker resumeUsageChecker;
    private final Clock clock;

    @Transactional
    public void delete(Long userId, Long resumeId) {
        // 세션 생성과의 직렬화 지점 — user 행 잠금 + 활성 재확인 (유저 상태 경로 공통 관례)
        userRepositoryService.lockActive(userId);
        // 존재(404) → 소유(403) — 이미 삭제된 이력서는 @SQLRestriction으로 404에 수렴한다
        Resume resume = resumeRepositoryService.getOwned(userId, resumeId);
        // 진행 중 면접에서 사용 중인 이력서는 삭제 불가 — R013 (면접이 검색할 청크 보호)
        if (resumeUsageChecker.isInUse(resumeId)) {
            throw new BusinessException(ErrorCode.RESUME_IN_USE);
        }
        resume.softDelete(clock.instant());
    }
}
