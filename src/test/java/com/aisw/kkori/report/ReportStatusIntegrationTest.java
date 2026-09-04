package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.report.domain.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리포트 생성 상태 REST 조회 통합 테스트 (docs/requirements/report/report.md §5 검증 기준).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReportFixtures.class})
class ReportStatusIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Autowired MockMvc mockMvc;
    @Autowired ReportFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.deleteAll();
    }

    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    // ─── 검증 기준 (PRD §5) ───

    @ParameterizedTest(name = "{0} 상태에서도 조회된다")
    @EnumSource(ReportStatus.class)
    @DisplayName("상태 조회는 상세와 달리 모든 상태에서 가능하다 (409 게이트 없음)")
    void statusIsReadableInAnyState(ReportStatus reportStatus) throws Exception {
        long reportId = fixtures.reportWithStatus(USER_ID, reportStatus);

        mockMvc.perform(get("/api/v1/reports/{reportId}/status", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value(reportId))
                .andExpect(jsonPath("$.data.status").value(reportStatus.name()))
                .andExpect(jsonPath("$.data.createdAt").value(notNullValue()));
    }

    @Test
    @DisplayName("FAILED 리포트의 상태 응답에 실패 사유가 포함된다")
    void failedStatusContainsReason() throws Exception {
        long reportId = fixtures.reportWithStatus(USER_ID, ReportStatus.FAILED);

        mockMvc.perform(get("/api/v1/reports/{reportId}/status", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failedReason").value("재전달 임계 초과"))
                .andExpect(jsonPath("$.data.completedAt").value(nullValue()));
    }

    @Test
    @DisplayName("COMPLETED가 아닌 리포트의 failedReason·completedAt은 null이다")
    void inProgressStatusHasNullFields() throws Exception {
        long reportId = fixtures.reportWithStatus(USER_ID, ReportStatus.PROCESSING);

        mockMvc.perform(get("/api/v1/reports/{reportId}/status", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedReason").value(nullValue()))
                .andExpect(jsonPath("$.data.completedAt").value(nullValue()));
    }

    @Test
    @DisplayName("존재하지 않는 리포트의 상태 조회는 404(RP001)다")
    void missingReportIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{reportId}/status", 999_999).with(authOf(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RP001"));
    }

    @Test
    @DisplayName("다른 사용자의 리포트 상태 조회는 403(RP002)이다")
    void foreignReportIsForbidden() throws Exception {
        long reportId = fixtures.reportWithStatus(OTHER_USER_ID, ReportStatus.PROCESSING);

        mockMvc.perform(get("/api/v1/reports/{reportId}/status", reportId).with(authOf(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RP002"));
    }
}
