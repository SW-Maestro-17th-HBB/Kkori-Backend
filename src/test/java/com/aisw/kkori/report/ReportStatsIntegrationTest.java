package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.report.domain.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리포트 통계 조회 통합 테스트 (docs/requirements/report/report.md §6 검증 기준 1:1).
 *
 * <p>월 경계 검증은 실행 시점의 Asia/Seoul 기준 현재 달을 기준으로 완료 시각을 만들어
 * 어느 날짜에 실행해도 결정적으로 동작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReportFixtures.class})
class ReportStatsIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired ReportFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.deleteAll();
    }

    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    /** 이번 달 day일 12:00(KST) 완료로 만들기 위한 생성 시각 (fixtures가 완료를 생성+180초로 기록). */
    private Instant completedThisMonthAt(int day) {
        return YearMonth.now(SEOUL).atDay(day).atTime(12, 0).atZone(SEOUL).toInstant().minusSeconds(180);
    }

    private Instant completedLastMonthAt(int day) {
        return YearMonth.now(SEOUL).minusMonths(1).atDay(day).atTime(12, 0)
                .atZone(SEOUL).toInstant().minusSeconds(180);
    }

    // ─── 검증 기준 (PRD §6) ───

    @Test
    @DisplayName("완료 리포트가 0건이면 totalCount=0, 수치는 null, 배열은 빈 배열이다")
    void emptyStatsWhenNoCompletedReports() throws Exception {
        fixtures.reportWithStatus(USER_ID, ReportStatus.PROCESSING);  // 미완성만 존재

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.avgScore").value(nullValue()))
                .andExpect(jsonPath("$.data.bestScore").value(nullValue()))
                .andExpect(jsonPath("$.data.monthlyDelta").value(nullValue()))
                .andExpect(jsonPath("$.data.trend", hasSize(0)))
                .andExpect(jsonPath("$.data.axisAverages.logicScore").value(nullValue()))
                .andExpect(jsonPath("$.data.weaknessSegments", hasSize(0)));
    }

    @Test
    @DisplayName("COMPLETED만 집계된다 — 진행 중·실패 리포트는 평균·횟수에 영향이 없다")
    void onlyCompletedReportsAreAggregated() throws Exception {
        fixtures.completedReport(USER_ID, 80, completedThisMonthAt(1));
        fixtures.completedReport(USER_ID, 91, completedThisMonthAt(2));
        fixtures.reportWithStatus(USER_ID, ReportStatus.PROCESSING);
        fixtures.reportWithStatus(USER_ID, ReportStatus.FAILED);

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.avgScore").value(86))  // (80+91)/2 = 85.5 → 86
                .andExpect(jsonPath("$.data.bestScore").value(91));
    }

    @Test
    @DisplayName("본인 리포트만 집계된다")
    void statsAreScopedToOwner() throws Exception {
        fixtures.completedReport(USER_ID, 70, completedThisMonthAt(1));
        fixtures.completedReport(OTHER_USER_ID, 100, completedThisMonthAt(2));

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.avgScore").value(70))
                .andExpect(jsonPath("$.data.bestScore").value(70));
    }

    @Test
    @DisplayName("trend는 완료 시각 오름차순 최대 12개 — 초과분은 오래된 것부터 제외된다")
    void trendIsAscendingAndCapped() throws Exception {
        for (int day = 1; day <= 13; day++) {
            fixtures.completedReport(USER_ID, 60 + day, completedThisMonthAt(day));
        }

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trend", hasSize(12)))
                .andExpect(jsonPath("$.data.trend[0].overallScore").value(62))   // day=1(61) 제외
                .andExpect(jsonPath("$.data.trend[11].overallScore").value(73));
    }

    @Test
    @DisplayName("monthlyDelta는 이번 달 평균 − 지난달 평균이다 (Asia/Seoul 월 경계)")
    void monthlyDeltaComparesThisAndLastMonth() throws Exception {
        fixtures.completedReport(USER_ID, 68, completedLastMonthAt(10));
        fixtures.completedReport(USER_ID, 72, completedLastMonthAt(20));  // 지난달 평균 70
        fixtures.completedReport(USER_ID, 80, completedThisMonthAt(1));   // 이번 달 평균 80

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthlyDelta").value(10));
    }

    @Test
    @DisplayName("monthlyDelta가 정확히 0.5 하락이면 -1로 반올림된다 (0으로 뭉개지지 않음)")
    void monthlyDeltaRoundsHalfAwayFromZero() throws Exception {
        fixtures.completedReport(USER_ID, 80, completedLastMonthAt(10));   // 지난달 평균 80
        fixtures.completedReport(USER_ID, 79, completedThisMonthAt(1));
        fixtures.completedReport(USER_ID, 80, completedThisMonthAt(2));    // 이번 달 평균 79.5

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthlyDelta").value(-1));  // -0.5 → -1 (절대값 기준 반올림)
    }

    @Test
    @DisplayName("이번 달·지난달 중 한쪽에 완료 리포트가 없으면 monthlyDelta는 null이다")
    void monthlyDeltaIsNullWhenAMonthIsMissing() throws Exception {
        fixtures.completedReport(USER_ID, 80, completedThisMonthAt(1));  // 지난달 없음

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthlyDelta").value(nullValue()));
    }

    @Test
    @DisplayName("월 경계는 Asia/Seoul 기준이다 — KST 1일 00:30 완료(UTC로는 전월)는 이번 달로 집계된다")
    void monthBoundaryUsesSeoulTimezone() throws Exception {
        // 이번 달 1일 00:30 KST = UTC 기준 전월 말일 15:30 — UTC로 집계하면 지난달로 새는 시각
        Instant boundary = YearMonth.now(SEOUL).atDay(1).atTime(0, 30).atZone(SEOUL)
                .toInstant().minusSeconds(180);
        fixtures.completedReport(USER_ID, 90, boundary);              // 이번 달이어야 함
        fixtures.completedReport(USER_ID, 70, completedLastMonthAt(15));  // 지난달 70

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthlyDelta").value(20));  // 90 − 70
    }

    @Test
    @DisplayName("축별 평균에서 전달력은 평가된 리포트만 모수로 하고, 전부 미평가면 null이다")
    void axisAveragesUseEvaluatedDeliveryOnly() throws Exception {
        fixtures.evaluatedReport(USER_ID, 74);    // 텍스트 85/72/88 + 전달력 74
        fixtures.evaluatedReport(USER_ID, null);  // 텍스트 85/72/88 + 전달력 미평가

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.axisAverages.logicScore").value(85))
                .andExpect(jsonPath("$.data.axisAverages.specificityScore").value(72))
                .andExpect(jsonPath("$.data.axisAverages.technicalAccuracyScore").value(88))
                .andExpect(jsonPath("$.data.axisAverages.deliveryScore").value(74));  // 모수 1건
    }

    @Test
    @DisplayName("weaknessSegments는 완료 리포트들의 태그 요약 합산이다 (빈도 내림차순)")
    void weaknessSegmentsAreSummedAcrossReports() throws Exception {
        fixtures.evaluatedReport(USER_ID, null);                       // 두괄식 부족 3, 근거 부족 2
        fixtures.completedReport(USER_ID, 80, completedThisMonthAt(1)); // 두괄식 부족 2

        mockMvc.perform(get("/api/v1/reports/stats").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weaknessSegments", hasSize(2)))
                .andExpect(jsonPath("$.data.weaknessSegments[0].tag").value("두괄식 부족"))
                .andExpect(jsonPath("$.data.weaknessSegments[0].count").value(5))
                .andExpect(jsonPath("$.data.weaknessSegments[1].tag").value("근거 부족"))
                .andExpect(jsonPath("$.data.weaknessSegments[1].count").value(2));
    }
}
