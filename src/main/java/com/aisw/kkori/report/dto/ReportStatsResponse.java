package com.aisw.kkori.report.dto;

import com.aisw.kkori.report.domain.WeaknessTagCount;

import java.time.Instant;
import java.util.List;

/**
 * 리포트 통계 응답 (docs/requirements/report/report.md §6).
 *
 * <p>집계 대상은 본인의 COMPLETED 리포트뿐이다. 완료 리포트가 0건이면 totalCount=0,
 * 수치는 null, 배열은 빈 배열이다. 평균·차이 값은 소수점 첫째 자리에서 반올림한 정수이며,
 * 월 경계(monthlyDelta)는 Asia/Seoul 기준이다.
 */
public record ReportStatsResponse(
        long totalCount,
        Integer avgScore,
        Integer bestScore,
        Integer monthlyDelta,
        List<TrendPoint> trend,
        AxisAverages axisAverages,
        List<WeaknessTagCount> weaknessSegments
) {

    /** 점수 추이의 한 점 — 완료 시각 오름차순, 최대 12개. */
    public record TrendPoint(Instant completedAt, Integer overallScore) {
    }

    /** 축별 평균 — deliveryScore는 전달력이 평가된 리포트만 모수로 하며, 없으면 null. */
    public record AxisAverages(
            Integer logicScore,
            Integer specificityScore,
            Integer technicalAccuracyScore,
            Integer deliveryScore
    ) {
    }

    public static ReportStatsResponse empty() {
        return new ReportStatsResponse(0, null, null, null,
                List.of(), new AxisAverages(null, null, null, null), List.of());
    }
}
