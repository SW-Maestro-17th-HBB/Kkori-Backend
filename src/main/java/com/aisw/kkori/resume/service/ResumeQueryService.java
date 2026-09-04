package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.response.PageResponse;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.dto.ResumeSummaryResponse;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이력서 목록 조회 (docs/requirements/resume/resume.md §2).
 *
 * <p>본인 이력서만, createdAt 내림차순 고정. 항목은 UI 소비 최소 필드만 —
 * 미리보기(structuredData)는 행 펼침 시 {@code GET /{resumeId}/parsed}가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class ResumeQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ResumeRepositoryService resumeRepositoryService;

    @Transactional(readOnly = true)
    public PageResponse<ResumeSummaryResponse> getList(Long userId, String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        AnalysisStatus filter = parseStatus(status);
        PageRequest pageable = PageRequest.of(page, size);
        Page<ResumeSummaryResponse> summaries = (filter == null)
                ? resumeRepositoryService.findSummariesByUserId(userId, pageable)
                : resumeRepositoryService.findSummariesByUserIdAndStatus(userId, filter, pageable);
        return PageResponse.from(summaries);
    }

    /** 상태 필터 파싱 — 미지정(null·공백)은 필터 없음, enum에 없는 값은 400 R012 (PRD §2 검증 기준). */
    private static AnalysisStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AnalysisStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_STATUS);
        }
    }
}
