package com.aisw.kkori.report.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportFeedback;
import com.aisw.kkori.report.domain.ReportScore;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.dto.ReportDetailResponse;
import com.aisw.kkori.report.repository.ReportFeedbackRepository;
import com.aisw.kkori.report.repository.ReportRepository;
import com.aisw.kkori.report.repository.ReportScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 리포트 조회 서비스 (docs/requirements/report/report.md §3).
 *
 * <p>리포트 데이터는 전부 Worker가 쓰고 Spring은 읽기만 한다.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /**
     * AI 분석 한계 안내 문구 — 서버 관리 고정 상수(PRD §3). 확정 문구는 HBB1-193에서 교체.
     */
    static final String AI_DISCLAIMER = "AI 분석 결과는 참고용이며 실제 면접 평가와 다를 수 있습니다.";

    private final ReportRepository reportRepository;
    private final ReportScoreRepository reportScoreRepository;
    private final ReportFeedbackRepository reportFeedbackRepository;

    @Transactional(readOnly = true)
    public ReportDetailResponse getDetail(Long userId, Long reportId) {
        Report report = findOwnedCompleted(userId, reportId);
        // COMPLETED 리포트에는 텍스트 3축 점수가 반드시 존재한다(Worker가 한 트랜잭션으로 저장).
        ReportScore score = reportScoreRepository.findByReportId(report.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        List<ReportFeedback> feedbacks =
                reportFeedbackRepository.findByReportIdOrderByQuestionNumberAsc(report.getId());
        return ReportDetailResponse.of(report, score, feedbacks, AI_DISCLAIMER);
    }

    /**
     * 본인 소유 + COMPLETED 검증. 순서: 존재(404) → 소유(403) → 상태(409).
     * 타인의 리포트는 존재를 숨기지 않고 403으로 명확히 거부한다(이력서 R009 선례).
     */
    private Report findOwnedCompleted(Long userId, Long reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        if (!report.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.REPORT_FORBIDDEN);
        }
        if (report.getStatus() == ReportStatus.FAILED) {
            throw new BusinessException(ErrorCode.REPORT_GENERATION_FAILED);
        }
        if (report.getStatus() != ReportStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.REPORT_GENERATION_IN_PROGRESS);
        }
        return report;
    }
}
