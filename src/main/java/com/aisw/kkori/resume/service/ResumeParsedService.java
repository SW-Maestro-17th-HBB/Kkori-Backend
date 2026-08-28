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
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파싱 결과 조회·수정·재분석 오케스트레이션 (docs/requirements/resume/resume.md §4).
 *
 * <p>세 API 모두 같은 접근 가드({@link ResumeRepositoryService})를 통과한다: 존재(404) → 소유(403)
 * → 상태(409). 조회·수정은 EMBEDDED에서만, 재분석은 EMBEDDED(REINDEX)·FAILED(FULL)에서만
 * 허용된다. 수정은 저장만 한다 — 색인 반영은 사용자가 재분석을 눌러야 일어난다 (수정 ≠ 재분석).
 *
 * <p>수정·재분석은 <b>user 행 잠금을 먼저 획득</b>해 면접 세션 생성과 직렬화한다
 * (interview-session-creation.md — 이력서 사용 중 차단): 잠금 없이 진행하면 세션 생성의
 * EMBEDDED 확인과 이쪽의 상태 변경이 엇갈려 무효 이력서를 참조한 세션이 생길 수 있다.
 * 잠금 순서는 user → resume_analysis_status.
 */
@Service
@RequiredArgsConstructor
public class ResumeParsedService {

    /** 수정 payload 상한 (PRD §4). Content-Length는 조작 가능하므로 서버가 직렬화해 실측한다. */
    private static final int MAX_STRUCTURED_DATA_BYTES = 100 * 1024;

    private final ResumeRepositoryService resumeRepositoryService;
    private final UserRepositoryService userRepositoryService;
    private final ResumeUsageChecker resumeUsageChecker;
    private final ResumeAnalysisRequestPublisher analysisRequestPublisher;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ResumeParsedResponse getParsed(Long userId, Long resumeId) {
        Resume resume = resumeRepositoryService.findAuthorized(userId, resumeId);
        ResumeAnalysisStatus status = resumeRepositoryService.statusOf(resumeId);
        resumeRepositoryService.requireEmbedded(status);
        return ResumeParsedResponse.of(resume, status.getParseStatus());
    }

    @Transactional
    public ResumeParsedResponse updateParsed(Long userId, Long resumeId, StructuredData structuredData) {
        lockActiveUser(userId);
        Resume resume = resumeRepositoryService.findAuthorized(userId, resumeId);
        ResumeAnalysisStatus status = resumeRepositoryService.lockedStatusOf(resumeId);
        // 상태 검사(이미 조회한 값)를 먼저 — 세션 존재 검사는 인덱스 없는 스캔이라 통과 요청에만 수행한다
        resumeRepositoryService.requireEmbedded(status);
        requireNotInUse(resumeId);
        requireWithinSizeLimit(structuredData);
        resume.updateStructuredData(structuredData);
        // updatedAt은 auditing이 flush 시점에 갱신한다 — 응답에 이번 저장 시각을 담으려면 DTO 생성 전에 flush 필요
        resumeRepositoryService.flushResumes();
        return ResumeParsedResponse.of(resume, status.getParseStatus());
    }

    @Transactional
    public ResumeReanalyzeResponse reanalyze(Long userId, Long resumeId) {
        lockActiveUser(userId);
        Resume resume = resumeRepositoryService.findAuthorized(userId, resumeId);
        ResumeAnalysisStatus status = resumeRepositoryService.lockedStatusOf(resumeId);

        AnalysisMode mode = switch (status.getParseStatus()) {
            case EMBEDDED -> AnalysisMode.REINDEX;   // 수정 반영 — DB structuredData부터 청킹·색인
            case FAILED -> AnalysisMode.FULL;        // 실패 복구 — S3 원본부터 전체 파이프라인
            default -> throw new BusinessException(ErrorCode.RESUME_ANALYSIS_IN_PROGRESS);
        };
        // 상태 게이트(switch) 통과 후에만 세션 존재 검사 — updateParsed와 동일한 순서 원칙
        requireNotInUse(resumeId);

        // 상태 재설정과 발행은 같은 트랜잭션 — restartFor javadoc의 계약(Worker 회수 규칙 오인 방지) 참조
        status.restartFor(mode);
        analysisRequestPublisher.publish(new ResumeParseRequestedMessage(
                resume.getId(), userId, resume.getOriginalFileBucket(), resume.getOriginalFileKey(), mode));

        return new ResumeReanalyzeResponse(resume.getId(), status.getParseStatus());
    }

    /** 세션 생성과의 직렬화 지점 — user 행 잠금 + 활성 재확인 (유저 상태 경로 공통 관례). */
    private void lockActiveUser(Long userId) {
        userRepositoryService.findActiveWithLock(userId);
    }

    /** 진행 중 면접에서 사용 중인 이력서는 변경 불가 — R013 (면접이 검색할 청크 보호, 판정은 세션 도메인 구현). */
    private void requireNotInUse(Long resumeId) {
        if (resumeUsageChecker.isInUse(resumeId)) {
            throw new BusinessException(ErrorCode.RESUME_IN_USE);
        }
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
