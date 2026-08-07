package com.aisw.kkori.report.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportFeedback;
import com.aisw.kkori.report.domain.ReportScore;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.domain.WeaknessTagCount;
import com.aisw.kkori.global.response.PageResponse;
import com.aisw.kkori.report.dto.ReportDetailResponse;
import com.aisw.kkori.report.dto.ReportGenerationRequestedMessage;
import com.aisw.kkori.report.dto.ReportRegenerateResponse;
import com.aisw.kkori.report.dto.ReportStatsResponse;
import com.aisw.kkori.report.dto.ReportStatusResponse;
import com.aisw.kkori.report.dto.ReportSummaryResponse;
import com.aisw.kkori.report.dto.ReportTimelineResponse;
import com.aisw.kkori.report.dto.TranscriptUtterance;
import com.aisw.kkori.report.repository.ReportRepository;
import com.aisw.kkori.report.repository.ReportScoreRepository;
import com.aisw.kkori.report.repositoryservice.ReportRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 리포트 조회·재생성 서비스 (docs/requirements/report/report.md §3).
 *
 * <p>리포트 데이터는 전부 Worker가 쓰고 Spring은 읽기만 한다 — 유일한 예외가
 * FAILED 재생성(이전 런 흔적 초기화 + PENDING 전환 + 생성 요청 재발행)이다.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /**
     * AI 분석 한계 안내 문구 — 서버 관리 고정 상수(PRD §3). 확정 문구는 HBB1-193에서 교체.
     */
    static final String AI_DISCLAIMER = "AI 분석 결과는 참고용이며 실제 면접 평가와 다를 수 있습니다.";

    // getList·getStats·getDetail의 직접 의존은 repositoryService 일괄 리팩터링 때 전환 예정
    private final ReportRepository reportRepository;
    private final ReportScoreRepository reportScoreRepository;
    private final ReportRepositoryService reportRepositoryService;
    private final ReportGenerationRequestPublisher generationRequestPublisher;

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
        Report report = reportRepositoryService.findOwnedCompleted(userId, reportId);
        // COMPLETED 리포트에는 텍스트 3축 점수가 반드시 존재한다(Worker가 한 트랜잭션으로 저장).
        ReportScore score = reportScoreRepository.findByReportId(report.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        List<ReportFeedback> feedbacks = reportRepositoryService.findFeedbacks(report.getId());
        return ReportDetailResponse.of(report, score, feedbacks, AI_DISCLAIMER);
    }

    /**
     * 질문-답변 타임라인 조회 (PRD §4). 접근 규칙은 상세와 동일 — 404 → 403 → 409.
     */
    @Transactional(readOnly = true)
    public ReportTimelineResponse getTimeline(Long userId, Long reportId) {
        Report report = reportRepositoryService.findOwnedCompleted(userId, reportId);
        List<TranscriptUtterance> utterances =
                reportRepositoryService.getUtterances(report.getInterviewSessionId());
        List<ReportFeedback> feedbacks = reportRepositoryService.findFeedbacks(report.getId());
        return ReportTimelineResponse.of(utterances, feedbacks);
    }


    /**
     * FAILED 재생성 (PRD §1). 이전 런 흔적 초기화·PENDING 전환·생성 요청 재발행을 한 트랜잭션으로
     * 처리한다 — 발행 실패 시 전부 롤백되어 FAILED가 유지된다(다시 시도 가능, Outbox 미도입 정책).
     * 리포트 행은 잠그고 읽는다 — 연속 클릭 등 동시 요청은 한 건만 통과하고 나머지는 409로 거부된다.
     */
    @Transactional
    public ReportRegenerateResponse regenerate(Long userId, Long reportId) {
        Report report = reportRepositoryService.findOwnedFailedForUpdate(userId, reportId);
        report.restartForRegeneration();
        reportRepositoryService.updateJobRequestedAtToNow(report.getId());
        generationRequestPublisher.publishForRegeneration(
                report.getId(), new ReportGenerationRequestedMessage(report.getInterviewSessionId()));
        return new ReportRegenerateResponse(report.getId(), report.getStatus());
    }

    /** monthlyDelta의 월 경계 기준 시간대 (PRD §6). */
    private static final ZoneId STATS_ZONE = ZoneId.of("Asia/Seoul");

    /** 점수 추이(trend)의 최대 점 수 — 화면 그래프 폭 기준 (PRD §6 제약). */
    private static final int TREND_LIMIT = 12;

    /**
     * 통계 조회 (PRD §6). 집계 대상은 본인의 COMPLETED 리포트뿐이다.
     * 사용자당 완료 리포트가 수십 건 규모인 MVP에서는 전량 로드 후 계산으로 충분하다(성능 요구사항).
     */
    @Transactional(readOnly = true)
    public ReportStatsResponse getStats(Long userId) {
        List<Report> completed =
                reportRepository.findByUserIdAndStatusOrderByCompletedAtAscIdAsc(userId, ReportStatus.COMPLETED);
        if (completed.isEmpty()) {
            return ReportStatsResponse.empty();
        }
        List<Integer> overalls = completed.stream().map(Report::getOverallScore)
                .filter(Objects::nonNull).toList();
        return new ReportStatsResponse(
                completed.size(),
                averageRounded(overalls),
                overalls.stream().max(Comparator.naturalOrder()).orElse(null),
                monthlyDelta(completed),
                trend(completed),
                axisAverages(completed),
                weaknessSegments(completed)
        );
    }

    /** 이번 달 평균 − 지난달 평균 (Asia/Seoul 월 경계). 어느 한쪽에 완료 리포트가 없으면 null. */
    private Integer monthlyDelta(List<Report> completed) {
        YearMonth thisMonth = YearMonth.now(STATS_ZONE);
        List<Integer> current = overallsInMonth(completed, thisMonth);
        List<Integer> previous = overallsInMonth(completed, thisMonth.minusMonths(1));
        if (current.isEmpty() || previous.isEmpty()) {
            return null;
        }
        return roundToInt(average(current) - average(previous));
    }

    private List<Integer> overallsInMonth(List<Report> completed, YearMonth month) {
        return completed.stream()
                .filter(r -> r.getCompletedAt() != null
                        && YearMonth.from(r.getCompletedAt().atZone(STATS_ZONE)).equals(month))
                .map(Report::getOverallScore)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 최근 완료 리포트의 시계열 — 완료 시각 오름차순, 최대 12개(초과 시 오래된 것부터 제외). */
    private List<ReportStatsResponse.TrendPoint> trend(List<Report> completed) {
        int from = Math.max(0, completed.size() - TREND_LIMIT);
        return completed.subList(from, completed.size()).stream()
                .map(r -> new ReportStatsResponse.TrendPoint(r.getCompletedAt(), r.getOverallScore()))
                .toList();
    }

    /**
     * 축별 평균. 텍스트 3축은 완료 리포트의 REPORT_SCORES 평균, 전달력은 평가된
     * 리포트(deliveryScore 존재)만 모수로 하고 전부 미평가면 null이다.
     */
    private ReportStatsResponse.AxisAverages axisAverages(List<Report> completed) {
        List<Long> ids = completed.stream().map(Report::getId).toList();
        List<ReportScore> scores = reportScoreRepository.findByReportIdIn(ids);
        List<Integer> deliveries = completed.stream().map(Report::getDeliveryScore)
                .filter(Objects::nonNull).toList();
        return new ReportStatsResponse.AxisAverages(
                averageRounded(scores.stream().map(ReportScore::getLogicScore).toList()),
                averageRounded(scores.stream().map(ReportScore::getSpecificityScore).toList()),
                averageRounded(scores.stream().map(ReportScore::getTechnicalAccuracyScore).toList()),
                averageRounded(deliveries)
        );
    }

    /** 완료 리포트들의 weakness_tag_summary를 태그별로 합산한다 — 빈도 내림차순, 동률은 태그명 순으로 고정. */
    private List<WeaknessTagCount> weaknessSegments(List<Report> completed) {
        Map<String, Integer> counts = new HashMap<>();
        for (Report report : completed) {
            List<WeaknessTagCount> summary = report.getWeaknessTagSummary();
            if (summary == null) {
                continue;
            }
            for (WeaknessTagCount tag : summary) {
                counts.merge(tag.tag(), tag.count(), Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new WeaknessTagCount(e.getKey(), e.getValue()))
                .toList();
    }

    private Integer averageRounded(List<Integer> values) {
        return values.isEmpty() ? null : roundToInt(average(values));
    }

    private double average(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).average().orElseThrow();
    }

    private int roundToInt(double value) {
        // 절대값 기준 반올림 — Math.round는 음수 .5를 0 쪽으로 올려, 정확히 .5 하락한
        // monthlyDelta가 "변화 없음(0)"으로 표시된다 (리뷰 반영)
        return value < 0 ? -(int) Math.round(-value) : (int) Math.round(value);
    }

    /**
     * 생성 상태 조회 (PRD §5) — SSE 유실·재연결 시 동기화용.
     * 상세 조회와 달리 상태 게이트(409)가 없다 — 상태 확인이 목적이므로 모든 상태에서 조회 가능.
     */
    @Transactional(readOnly = true)
    public ReportStatusResponse getStatus(Long userId, Long reportId) {
        return ReportStatusResponse.from(reportRepositoryService.findOwned(userId, reportId));
    }
}
