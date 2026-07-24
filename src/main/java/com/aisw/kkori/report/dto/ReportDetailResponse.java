package com.aisw.kkori.report.dto;

import com.aisw.kkori.report.domain.ImprovementTask;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportFeedback;
import com.aisw.kkori.report.domain.ReportScore;
import com.aisw.kkori.report.domain.WeaknessTagCount;

import java.time.Instant;
import java.util.List;

/**
 * 리포트 상세 응답 (docs/requirements/report/report.md §3).
 *
 * <p>원본 이력서 참조(resumeId)와 사용자 간 백분위(rank)는 의도적으로 없다 —
 * 리포트는 평가 당시 스냅샷만 보여준다. 답변별 피드백은 타임라인 API 몫.
 */
public record ReportDetailResponse(
        Long reportId,
        String resumeFileName,
        Instant completedAt,
        Integer overallScore,
        Scores scores,
        int questionCount,
        String summary,
        List<WeaknessTagCount> weaknessTagSummary,
        List<ImprovementTask> improvementTasks,
        String aiDisclaimer
) {

    /** 축별 점수 — 텍스트 3축은 REPORT_SCORES, 전달력은 REPORTS에서 조립. 전달력 미평가면 null. */
    public record Scores(
            Integer logicScore,
            Integer specificityScore,
            Integer technicalAccuracyScore,
            Integer deliveryScore
    ) {
    }

    public static ReportDetailResponse of(Report report, ReportScore score,
                                          List<ReportFeedback> feedbacks, String aiDisclaimer) {
        List<ImprovementTask> improvementTasks = feedbacks.stream()
                .filter(feedback -> feedback.getImprovementTasks() != null)
                .flatMap(feedback -> feedback.getImprovementTasks().stream())
                .toList();
        return new ReportDetailResponse(
                report.getId(),
                report.getResumeFileNameSnapshot(),
                report.getCompletedAt(),
                report.getOverallScore(),
                new Scores(
                        score.getLogicScore(),
                        score.getSpecificityScore(),
                        score.getTechnicalAccuracyScore(),
                        report.getDeliveryScore()
                ),
                feedbacks.size(),
                report.getSummary(),
                report.getWeaknessTagSummary(),
                improvementTasks,
                aiDisclaimer
        );
    }
}
