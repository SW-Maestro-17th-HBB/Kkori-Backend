package com.aisw.kkori.resume.service;

import com.aisw.kkori.resume.domain.Resume;
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
 */
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {

    private final ResumeAccessGuard accessGuard;
    private final Clock clock;

    @Transactional
    public void delete(Long userId, Long resumeId) {
        // 존재(404) → 소유(403) — 이미 삭제된 이력서는 @SQLRestriction으로 404에 수렴한다
        Resume resume = accessGuard.findAuthorized(userId, resumeId);
        // TODO(면접 도메인): 진행 중인 면접 세션에서 사용 중인 이력서는 삭제 차단 (RESUME_IN_USE) — 세션 테이블 도입 시 구현
        resume.softDelete(clock.instant());
    }
}
