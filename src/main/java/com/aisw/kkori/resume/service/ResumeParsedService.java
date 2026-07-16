package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.domain.StructuredData;
import com.aisw.kkori.resume.dto.ResumeParsedResponse;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파싱 결과 조회·수정·재분석 오케스트레이션 (docs/requirements/resume/resume.md §4).
 *
 * <p>세 API 모두 같은 접근 가드를 통과한다: 존재(404) → 소유(403) → 상태(409).
 * 조회·수정은 EMBEDDED에서만, 재분석은 EMBEDDED(REINDEX)·FAILED(FULL)에서만 허용된다.
 * 수정은 저장만 한다 — 색인 반영은 사용자가 재분석을 눌러야 일어난다 (수정 ≠ 재분석).
 */
@Service
@RequiredArgsConstructor
public class ResumeParsedService {

    /** 수정 payload 상한 (PRD §4). Content-Length는 조작 가능하므로 서버가 직렬화해 실측한다. */
    private static final int MAX_STRUCTURED_DATA_BYTES = 100 * 1024;

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisStatusRepository statusRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ResumeParsedResponse getParsed(Long userId, Long resumeId) {
        Resume resume = findAuthorized(userId, resumeId);
        ResumeAnalysisStatus status = statusOf(resumeId);
        requireEmbedded(status);
        return ResumeParsedResponse.of(resume, status.getParseStatus());
    }

    @Transactional
    public ResumeParsedResponse updateParsed(Long userId, Long resumeId, StructuredData structuredData) {
        Resume resume = findAuthorized(userId, resumeId);
        ResumeAnalysisStatus status = statusOf(resumeId);
        requireEmbedded(status);
        // TODO(면접 도메인): 진행 중인 면접 세션에서 사용 중인 이력서는 수정 차단 (RESUME_IN_USE) — 세션 테이블 도입 시 구현
        requireWithinSizeLimit(structuredData);
        resume.updateStructuredData(structuredData);
        return ResumeParsedResponse.of(resume, status.getParseStatus());
    }

    /**
     * 공통 접근 가드 전반부: 존재(404) → 소유(403). 타인 이력서에 404가 아닌 403을 주는 것은
     * PRD §4 검증 기준이 명시한 계약이다.
     */
    private Resume findAuthorized(Long userId, Long resumeId) {
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        if (!resume.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.RESUME_FORBIDDEN);
        }
        return resume;
    }

    /** 상태 행은 업로드 트랜잭션에서 Resume과 함께 생성되므로, 없다는 것은 데이터 정합성 깨짐(서버 결함)이다. */
    private ResumeAnalysisStatus statusOf(Long resumeId) {
        return statusRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** 조회·수정 가드 후반부: EMBEDDED만 허용 — FAILED는 R011(재분석 유도), 그 외 진행 중은 R010(대기 유도). */
    private void requireEmbedded(ResumeAnalysisStatus status) {
        if (status.getParseStatus() == AnalysisStatus.EMBEDDED) {
            return;
        }
        throw new BusinessException(status.getParseStatus() == AnalysisStatus.FAILED
                ? ErrorCode.RESUME_ANALYSIS_FAILED
                : ErrorCode.RESUME_ANALYSIS_IN_PROGRESS);
    }

    private void requireWithinSizeLimit(StructuredData structuredData) {
        try {
            if (objectMapper.writeValueAsBytes(structuredData).length > MAX_STRUCTURED_DATA_BYTES) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
