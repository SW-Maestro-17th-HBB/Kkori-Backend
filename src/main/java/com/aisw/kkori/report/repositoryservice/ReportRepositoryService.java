package com.aisw.kkori.report.repositoryservice;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportFeedback;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.dto.TranscriptUtterance;
import com.aisw.kkori.report.repository.ReportFeedbackRepository;
import com.aisw.kkori.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 리포트 영속성 접근 계층 — 조회와 존재 검증·도메인 예외 변환을 담당한다.
 * service는 repository를 직접 의존하지 않고 이 계층을 거친다 (CLAUDE.md 패키지 구조 규칙).
 */
@Service
@RequiredArgsConstructor
public class ReportRepositoryService {

    private final ReportRepository reportRepository;
    private final ReportFeedbackRepository reportFeedbackRepository;
    private final TranscriptReader transcriptReader;

    /**
     * 본인 소유 검증 — 존재(404) → 소유(403) 순서.
     * 타인의 리포트는 존재를 숨기지 않고 403으로 명확히 거부한다(이력서 R009 선례).
     */
    public Report findOwned(Long userId, Long reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        if (!report.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.REPORT_FORBIDDEN);
        }
        return report;
    }

    /** 본인 소유 + COMPLETED 검증 — 존재(404) → 소유(403) → 상태(409) 순서. */
    public Report findOwnedCompleted(Long userId, Long reportId) {
        Report report = findOwned(userId, reportId);
        if (report.getStatus() == ReportStatus.FAILED) {
            throw new BusinessException(ErrorCode.REPORT_GENERATION_FAILED);
        }
        if (report.getStatus() != ReportStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.REPORT_GENERATION_IN_PROGRESS);
        }
        return report;
    }

    /** 답변별 피드백 — 질문 번호 오름차순. */
    public List<ReportFeedback> findFeedbacks(Long reportId) {
        return reportFeedbackRepository.findByReportIdOrderByQuestionNumberAsc(reportId);
    }

    /** 세션 대본 발화 — COMPLETED 리포트에 대본이 없는 것은 Worker 계약상 불가능한 상태라 500. */
    public List<TranscriptUtterance> getUtterances(long sessionId) {
        return transcriptReader.findUtterances(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
