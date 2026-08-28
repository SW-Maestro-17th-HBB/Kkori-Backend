package com.aisw.kkori.report.repositoryservice;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportFeedback;
import com.aisw.kkori.report.domain.ReportScore;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.dto.TranscriptUtterance;
import com.aisw.kkori.report.repository.ReportFeedbackRepository;
import com.aisw.kkori.report.repository.ReportRepository;
import com.aisw.kkori.report.repository.ReportScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 리포트 영속성 접근 계층 — 조회와 존재 검증·도메인 예외 변환을 담당한다.
 * service는 repository를 직접 의존하지 않고 이 계층을 거친다 (CLAUDE.md 패키지 구조 규칙).
 */
@Service
@RequiredArgsConstructor
public class ReportRepositoryService {

    private final ReportRepository reportRepository;
    private final ReportScoreRepository reportScoreRepository;
    private final ReportFeedbackRepository reportFeedbackRepository;
    private final TranscriptReader transcriptReader;
    private final JdbcReportJobWriter jdbcReportJobWriter;

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

    /**
     * 본인 소유 + FAILED 검증(재생성 전용) — 존재(404) → 소유(403) → 상태(409) 순서.
     * 행을 잠그고 읽는다(SELECT ... FOR UPDATE) — 상태 검사와 전환 커밋 사이에 다른 요청
     * (연속 클릭 등)이 상태를 바꾸면 낡은 판정으로 통과하기 때문(이력서 재분석의 선례, PRD §1).
     * 잠금은 트랜잭션이 끝날 때 풀리므로 반드시 호출자의 쓰기 트랜잭션 안에서 호출해야 한다
     * — 트랜잭션 없이 호출하면 잠금 쿼리가 예외로 실패한다.
     */
    public Report findOwnedFailedForUpdate(Long userId, Long reportId) {
        Report report = reportRepository.findForUpdateById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        if (!report.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.REPORT_FORBIDDEN);
        }
        if (report.getStatus() == ReportStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.REPORT_RETRY_NOT_ALLOWED);
        }
        if (report.getStatus() != ReportStatus.FAILED) {
            throw new BusinessException(ErrorCode.REPORT_GENERATION_IN_PROGRESS);
        }
        return report;
    }

    /** 재생성 시 Job의 requested_at을 현재 시각으로 갱신 — Worker 소유 테이블이라 네이티브로 쓴다. */
    public void updateJobRequestedAtToNow(long reportId) {
        jdbcReportJobWriter.updateRequestedAtToNow(reportId);
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

    public Page<Report> findPage(Long userId, ReportStatus status, Pageable pageable) {
        return reportRepository.findPage(userId, status, pageable);
    }

    public Page<Report> findPageOrderByOverallScoreDesc(Long userId, ReportStatus status, Pageable pageable) {
        return reportRepository.findPageOrderByOverallScoreDesc(userId, status, pageable);
    }

    public Page<Report> findPageOrderByOverallScoreAsc(Long userId, ReportStatus status, Pageable pageable) {
        return reportRepository.findPageOrderByOverallScoreAsc(userId, status, pageable);
    }

    public List<Report> findByUserIdAndStatusOrderByCompletedAtAscIdAsc(Long userId, ReportStatus status) {
        return reportRepository.findByUserIdAndStatusOrderByCompletedAtAscIdAsc(userId, status);
    }

    /** 텍스트 3축 점수 — COMPLETED 리포트에 점수가 없는 것은 Worker 계약상 불가능한 상태라 500. */
    public ReportScore getScore(Long reportId) {
        return reportScoreRepository.findByReportId(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    public List<ReportScore> findScoresByReportIdIn(Collection<Long> reportIds) {
        return reportScoreRepository.findByReportIdIn(reportIds);
    }
}
