package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이력서 공통 접근 가드: 존재(404) → 소유(403) → 분석 상태(409).
 *
 * <p>이력서 도메인({@code ResumeParsedService})과 세션 생성(면접은 EMBEDDED 이력서로만
 * 시작 — interview-session-creation.md 기능 1)이 같은 판정·에러 계약을 공유하기 위해
 * 추출했다. 에러 코드가 양쪽에서 갈리면 프론트 동선(재분석 유도 등)이 깨진다.
 */
@Component
@RequiredArgsConstructor
public class ResumeAccessGuard {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisStatusRepository statusRepository;

    /**
     * 존재(404) → 소유(403). 타인 이력서에 404가 아닌 403을 주는 것은 resume PRD §4의 계약이다.
     * soft delete된 이력서는 {@code @SQLRestriction}으로 조회되지 않아 404로 수렴한다.
     */
    public Resume findAuthorized(Long userId, Long resumeId) {
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        if (!resume.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.RESUME_FORBIDDEN);
        }
        return resume;
    }

    /** 상태 행은 업로드 트랜잭션에서 Resume과 함께 생성되므로, 없다는 것은 데이터 정합성 깨짐(서버 결함)이다. */
    public ResumeAnalysisStatus statusOf(Long resumeId) {
        return statusRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /**
     * 상태 검사 후 행동(수정·재분석)하는 트랜잭션은 잠그고 읽는다 — 검사와 커밋 사이에 다른 요청이
     * 상태를 바꾸면 낡은 판정으로 통과하기 때문(check-then-act). 읽기 전용 경로는 잠그지 않는다.
     */
    public ResumeAnalysisStatus lockedStatusOf(Long resumeId) {
        return statusRepository.findForUpdateByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** EMBEDDED만 허용 — FAILED는 R011(재분석 유도), 그 외 진행 중은 R010(대기 유도). */
    public void requireEmbedded(ResumeAnalysisStatus status) {
        if (status.getParseStatus() == AnalysisStatus.EMBEDDED) {
            return;
        }
        throw new BusinessException(status.getParseStatus() == AnalysisStatus.FAILED
                ? ErrorCode.RESUME_ANALYSIS_FAILED
                : ErrorCode.RESUME_ANALYSIS_IN_PROGRESS);
    }
}
