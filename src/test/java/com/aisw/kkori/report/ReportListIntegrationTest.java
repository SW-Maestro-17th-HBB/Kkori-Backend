package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.domain.WeaknessTagCount;
import com.aisw.kkori.report.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리포트 목록 조회 통합 테스트 (docs/requirements/report/report.md §2 검증 기준 1:1).
 *
 * <p>생성 시각은 감사(Auditing)가 저장 시점으로 덮어쓰므로, 정렬 검증에 필요한 시각은
 * 저장 후 JdbcTemplate로 바꿔 재현한다(이력서 테스트 선례).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportListIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final Instant BASE = Instant.parse("2026-06-01T00:00:00Z");

    private static final AtomicLong SESSION_SEQ = new AtomicLong(2000);

    @Autowired MockMvc mockMvc;
    @Autowired ReportRepository reportRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
    }

    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    // ─── 픽스처 ───

    private long report(long userId, ReportStatus status, Integer overallScore, Instant createdAt) {
        boolean completed = status == ReportStatus.COMPLETED;
        Report report = reportRepository.save(Report.builder()
                .userId(userId)
                .interviewSessionId(SESSION_SEQ.incrementAndGet())
                .resumeId(10L)
                .status(status)
                .overallScore(completed ? overallScore : null)
                .summary(completed ? "총평" : null)
                .resumeFileNameSnapshot("백엔드_개발자_이력서.pdf")
                .weaknessTagSummary(completed ? List.of(new WeaknessTagCount("두괄식 부족", 2)) : null)
                .completedAt(completed ? createdAt.plusSeconds(180) : null)
                .build());
        jdbcTemplate.update("UPDATE reports SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), report.getId());
        return report.getId();
    }

    private long completedReport(long userId, Integer overallScore, Instant createdAt) {
        return report(userId, ReportStatus.COMPLETED, overallScore, createdAt);
    }

    // ─── 검증 기준 (PRD §2) ───

    @Test
    @DisplayName("목록이 page/size대로 페이지네이션된다")
    void paginates() throws Exception {
        completedReport(USER_ID, 70, BASE.plusSeconds(10));
        completedReport(USER_ID, 80, BASE.plusSeconds(20));
        completedReport(USER_ID, 90, BASE.plusSeconds(30));

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
        long oldest = completedReport(USER_ID, 70, BASE.plusSeconds(10));
        long newest = completedReport(USER_ID, 80, BASE.plusSeconds(30));
        long middle = completedReport(USER_ID, 90, BASE.plusSeconds(20));

        mockMvc.perform(get("/api/v1/reports").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportId").value(newest))
                .andExpect(jsonPath("$.data.content[1].reportId").value(middle))
                .andExpect(jsonPath("$.data.content[2].reportId").value(oldest));
    }

    @Test
    @DisplayName("overallScore 정렬에서 점수순으로 반환되고, null(미완성)은 방향과 무관하게 항상 뒤다")
    void overallScoreSortPutsNullLast() throws Exception {
        long low = completedReport(USER_ID, 70, BASE.plusSeconds(10));
        long high = completedReport(USER_ID, 90, BASE.plusSeconds(20));
        long pending = report(USER_ID, ReportStatus.PENDING, null, BASE.plusSeconds(30));

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
        long earlier = completedReport(USER_ID, 80, BASE.plusSeconds(10));
        long later = completedReport(USER_ID, 80, BASE.plusSeconds(20));

        mockMvc.perform(get("/api/v1/reports?sort=overallScore").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportId").value(later))
                .andExpect(jsonPath("$.data.content[1].reportId").value(earlier));
    }

    @Test
    @DisplayName("status 필터가 해당 상태만 반환하고, 잘못된 값은 400(C002)이다")
    void statusFilter() throws Exception {
        long completed = completedReport(USER_ID, 80, BASE.plusSeconds(10));
        report(USER_ID, ReportStatus.FAILED, null, BASE.plusSeconds(20));

        mockMvc.perform(get("/api/v1/reports?status=COMPLETED").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].reportId").value(completed));

        mockMvc.perform(get("/api/v1/reports?status=WRONG").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("size가 상한(100)을 넘으면 400(C002)이다")
    void sizeOverLimit() throws Exception {
        mockMvc.perform(get("/api/v1/reports?size=101").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("잘못된 sort·order 값은 400(C002)이다")
    void invalidSortOrOrder() throws Exception {
        mockMvc.perform(get("/api/v1/reports?sort=unknown").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        mockMvc.perform(get("/api/v1/reports?order=upward").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("생성 중·실패 리포트도 목록에 노출되고, 미완성 리포트의 점수·태그 요약은 null이다")
    void unfinishedReportsAreListed() throws Exception {
        report(USER_ID, ReportStatus.PROCESSING, null, BASE.plusSeconds(10));

        mockMvc.perform(get("/api/v1/reports").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.content[0].overallScore").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].weaknessTagSummary").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].completedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].resumeFileName").value("백엔드_개발자_이력서.pdf"));
    }

    @Test
    @DisplayName("다른 사용자의 리포트는 목록에 포함되지 않는다")
    void excludesOtherUsersReports() throws Exception {
        completedReport(OTHER_USER_ID, 95, BASE.plusSeconds(10));
        long mine = completedReport(USER_ID, 80, BASE.plusSeconds(20));

        mockMvc.perform(get("/api/v1/reports").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].reportId").value(mine));
    }
}
