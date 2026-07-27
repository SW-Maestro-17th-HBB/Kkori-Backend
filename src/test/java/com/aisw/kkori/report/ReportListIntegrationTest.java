package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.report.domain.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리포트 목록 조회 통합 테스트 (docs/requirements/report/report.md §2 검증 기준 1:1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReportFixtures.class})
class ReportListIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final Instant BASE = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ReportFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.deleteAll();
    }

    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    // ─── 검증 기준 (PRD §2) ───

    @Test
    @DisplayName("목록이 page/size대로 페이지네이션된다")
    void paginates() throws Exception {
        fixtures.completedReport(USER_ID, 70, BASE.plusSeconds(10));
        fixtures.completedReport(USER_ID, 80, BASE.plusSeconds(20));
        fixtures.completedReport(USER_ID, 90, BASE.plusSeconds(30));

        mockMvc.perform(get("/api/v1/reports?page=0&size=2").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(get("/api/v1/reports?page=1&size=2").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("기본 정렬은 생성 시각 내림차순이다")
    void defaultSortIsCreatedAtDesc() throws Exception {
        long oldest = fixtures.completedReport(USER_ID, 70, BASE.plusSeconds(10));
        long newest = fixtures.completedReport(USER_ID, 80, BASE.plusSeconds(30));
        long middle = fixtures.completedReport(USER_ID, 90, BASE.plusSeconds(20));

        mockMvc.perform(get("/api/v1/reports").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportId").value(newest))
                .andExpect(jsonPath("$.data.content[1].reportId").value(middle))
                .andExpect(jsonPath("$.data.content[2].reportId").value(oldest));
    }

    @Test
    @DisplayName("overallScore 정렬에서 점수순으로 반환되고, null(미완성)은 방향과 무관하게 항상 뒤다")
    void overallScoreSortPutsNullLast() throws Exception {
        long low = fixtures.completedReport(USER_ID, 70, BASE.plusSeconds(10));
        long high = fixtures.completedReport(USER_ID, 90, BASE.plusSeconds(20));
        long pending = fixtures.report(USER_ID, ReportStatus.PENDING, null, BASE.plusSeconds(30));

        mockMvc.perform(get("/api/v1/reports?sort=overallScore").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportId").value(high))
                .andExpect(jsonPath("$.data.content[1].reportId").value(low))
                .andExpect(jsonPath("$.data.content[2].reportId").value(pending));

        mockMvc.perform(get("/api/v1/reports?sort=overallScore&order=asc").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportId").value(low))
                .andExpect(jsonPath("$.data.content[1].reportId").value(high))
                .andExpect(jsonPath("$.data.content[2].reportId").value(pending));
    }

    @Test
    @DisplayName("overallScore 동점은 생성 시각 내림차순으로 순서가 고정된다")
    void overallScoreTieIsDeterministic() throws Exception {
        long earlier = fixtures.completedReport(USER_ID, 80, BASE.plusSeconds(10));
        long later = fixtures.completedReport(USER_ID, 80, BASE.plusSeconds(20));

        mockMvc.perform(get("/api/v1/reports?sort=overallScore").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportId").value(later))
                .andExpect(jsonPath("$.data.content[1].reportId").value(earlier));
    }

    @Test
    @DisplayName("status 필터가 해당 상태의 리포트만 반환한다")
    void statusFilter() throws Exception {
        long completed = fixtures.completedReport(USER_ID, 80, BASE.plusSeconds(10));
        fixtures.report(USER_ID, ReportStatus.FAILED, null, BASE.plusSeconds(20));

        mockMvc.perform(get("/api/v1/reports?status=COMPLETED").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].reportId").value(completed));
    }

    @ParameterizedTest(name = "{0} 이면 400 C002")
    @ValueSource(strings = {
            "status=WRONG",   // enum에 없는 상태값
            "sort=unknown",   // 지원하지 않는 정렬 키
            "order=upward",   // 지원하지 않는 정렬 방향
            "page=-1",        // 음수 페이지
            "size=0",         // 1 미만 크기
            "size=101",       // 상한(100) 초과
    })
    @DisplayName("잘못된 조회 파라미터는 400(C002)이다")
    void invalidParamsReturn400(String queryString) throws Exception {
        mockMvc.perform(get("/api/v1/reports?" + queryString).with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("생성 중·실패 리포트도 목록에 노출되고, 미완성 리포트의 점수·태그 요약은 null이다")
    void unfinishedReportsAreListed() throws Exception {
        fixtures.report(USER_ID, ReportStatus.PROCESSING, null, BASE.plusSeconds(10));

        mockMvc.perform(get("/api/v1/reports").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.content[0].overallScore").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].weaknessTagSummary").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].completedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].resumeFileName").value(ReportFixtures.RESUME_FILE_NAME));
    }

    @Test
    @DisplayName("다른 사용자의 리포트는 목록에 포함되지 않는다")
    void excludesOtherUsersReports() throws Exception {
        fixtures.completedReport(OTHER_USER_ID, 95, BASE.plusSeconds(10));
        long mine = fixtures.completedReport(USER_ID, 80, BASE.plusSeconds(20));

        mockMvc.perform(get("/api/v1/reports").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].reportId").value(mine));
    }
}
