package com.aisw.kkori.report.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportFeedback;
import com.aisw.kkori.report.domain.ReportScore;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.global.response.PageResponse;
import com.aisw.kkori.report.dto.ReportDetailResponse;
import com.aisw.kkori.report.dto.ReportStatusResponse;
import com.aisw.kkori.report.dto.ReportSummaryResponse;
import com.aisw.kkori.report.repository.ReportFeedbackRepository;
import com.aisw.kkori.report.repository.ReportRepository;
import com.aisw.kkori.report.repository.ReportScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** 이력서 목록과 동일한 상한 (ResumeQueryService 선례). */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 목록 조회 (PRD §2). 목록 항목은 REPORTS 단독으로 구성한다 — 생성 중·실패 리포트도 노출.
     * sort·order·페이지 값의 검증 실패는 400(INVALID_INPUT_VALUE)이다.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReportSummaryResponse> getList(Long userId, ReportStatus status,
                                                       String sort, String order, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        boolean descending = switch (order) {
            case "desc" -> true;
            case "asc" -> false;
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        };
        Page<Report> reports = switch (sort) {
            case "createdAt" -> {
                Sort.Direction direction = descending ? Sort.Direction.DESC : Sort.Direction.ASC;
                // 동시각 동점은 id로 순서를 고정한다 — 페이지 경계에서 항목이 흔들리지 않게
                Pageable pageable = PageRequest.of(page, size,
                        Sort.by(direction, "createdAt").and(Sort.by(direction, "id")));
                yield reportRepository.findPage(userId, status, pageable);
            }
            case "overallScore" -> {
                // null을 항상 뒤로 보내는 정렬은 쿼리에 고정되어 있어 Pageable에는 정렬을 싣지 않는다
                Pageable pageable = PageRequest.of(page, size);
                yield descending
                        ? reportRepository.findPageOrderByOverallScoreDesc(userId, status, pageable)
                        : reportRepository.findPageOrderByOverallScoreAsc(userId, status, pageable);
            }
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        };
        return PageResponse.from(reports.map(ReportSummaryResponse::from));
    }

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
     * 생성 상태 조회 (PRD §5) — SSE 유실·재연결 시 동기화용.
     * 상세 조회와 달리 상태 게이트(409)가 없다 — 상태 확인이 목적이므로 모든 상태에서 조회 가능.
     */
    @Transactional(readOnly = true)
    public ReportStatusResponse getStatus(Long userId, Long reportId) {
        return ReportStatusResponse.from(findOwned(userId, reportId));
    }

    /**
     * 본인 소유 + COMPLETED 검증. 순서: 존재(404) → 소유(403) → 상태(409).
     */
    private Report findOwnedCompleted(Long userId, Long reportId) {
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
     * 본인 소유 검증. 순서: 존재(404) → 소유(403).
     * 타인의 리포트는 존재를 숨기지 않고 403으로 명확히 거부한다(이력서 R009 선례).
     */
    private Report findOwned(Long userId, Long reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        if (!report.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.REPORT_FORBIDDEN);
        }
        return report;
    }
}
