package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.AnalysisMode;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.domain.StructuredData;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import com.aisw.kkori.resume.dto.ResumeParsedResponse;
import com.aisw.kkori.resume.dto.ResumeReanalyzeResponse;
import com.aisw.kkori.resume.repository.ResumeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파싱 결과 조회·수정·재분석 오케스트레이션 (docs/requirements/resume/resume.md §4).
 *
 * <p>세 API 모두 같은 접근 가드({@link ResumeAccessGuard})를 통과한다: 존재(404) → 소유(403)
 * → 상태(409). 조회·수정은 EMBEDDED에서만, 재분석은 EMBEDDED(REINDEX)·FAILED(FULL)에서만
 * 허용된다. 수정은 저장만 한다 — 색인 반영은 사용자가 재분석을 눌러야 일어난다 (수정 ≠ 재분석).
 */
@Service
@RequiredArgsConstructor
public class ResumeParsedService {

    /** 수정 payload 상한 (PRD §4). Content-Length는 조작 가능하므로 서버가 직렬화해 실측한다. */
    private static final int MAX_STRUCTURED_DATA_BYTES = 100 * 1024;

    private final ResumeRepository resumeRepository;
    private final ResumeAccessGuard accessGuard;
    private final ResumeAnalysisRequestPublisher analysisRequestPublisher;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ResumeParsedResponse getParsed(Long userId, Long resumeId) {
        Resume resume = accessGuard.findAuthorized(userId, resumeId);
        ResumeAnalysisStatus status = accessGuard.statusOf(resumeId);
        accessGuard.requireEmbedded(status);
        return ResumeParsedResponse.of(resume, status.getParseStatus());
    }

    @Transactional
    public ResumeParsedResponse updateParsed(Long userId, Long resumeId, StructuredData structuredData) {
        Resume resume = accessGuard.findAuthorized(userId, resumeId);
        ResumeAnalysisStatus status = accessGuard.lockedStatusOf(resumeId);
        accessGuard.requireEmbedded(status);
        // TODO(면접 도메인): 진행 중인 면접 세션에서 사용 중인 이력서는 수정 차단 (RESUME_IN_USE) — 세션 테이블 도입 시 구현
        requireWithinSizeLimit(structuredData);
        resume.updateStructuredData(structuredData);
        // updatedAt은 auditing이 flush 시점에 갱신한다 — 응답에 이번 저장 시각을 담으려면 DTO 생성 전에 flush 필요
        resumeRepository.flush();
        return ResumeParsedResponse.of(resume, status.getParseStatus());
    }

    @Transactional
    public ResumeReanalyzeResponse reanalyze(Long userId, Long resumeId) {
        Resume resume = accessGuard.findAuthorized(userId, resumeId);
        ResumeAnalysisStatus status = accessGuard.lockedStatusOf(resumeId);

        AnalysisMode mode = switch (status.getParseStatus()) {
            case EMBEDDED -> AnalysisMode.REINDEX;   // 수정 반영 — DB structuredData부터 청킹·색인
            case FAILED -> AnalysisMode.FULL;        // 실패 복구 — S3 원본부터 전체 파이프라인
            default -> throw new BusinessException(ErrorCode.RESUME_ANALYSIS_IN_PROGRESS);
        };

        // 상태 재설정과 발행은 같은 트랜잭션 — restartFor javadoc의 계약(Worker 회수 규칙 오인 방지) 참조
        status.restartFor(mode);
        analysisRequestPublisher.publish(new ResumeParseRequestedMessage(
                resume.getId(), userId, resume.getOriginalFileBucket(), resume.getOriginalFileKey(), mode));

        return new ResumeReanalyzeResponse(resume.getId(), status.getParseStatus());
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
