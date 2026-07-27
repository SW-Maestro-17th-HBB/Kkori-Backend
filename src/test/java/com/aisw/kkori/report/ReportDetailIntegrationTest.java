package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.report.domain.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리포트 상세 조회 통합 테스트 (docs/requirements/report/report.md §3 검증 기준 1:1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReportFixtures.class})
class ReportDetailIntegrationTest {

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

    // ─── 검증 기준 (PRD §3) ───

    @Test
    @DisplayName("COMPLETED 리포트 상세에 총평·축별 점수·overall·질문 수·약점 태그 요약·개선 과제·aiDisclaimer가 포함된다")
    void detailContainsAllFields() throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, 74);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reportId").value(reportId))
                .andExpect(jsonPath("$.data.resumeFileName").value(ReportFixtures.RESUME_FILE_NAME))
                .andExpect(jsonPath("$.data.overallScore").value(82))
                .andExpect(jsonPath("$.data.scores.logicScore").value(85))
                .andExpect(jsonPath("$.data.scores.specificityScore").value(72))
                .andExpect(jsonPath("$.data.scores.technicalAccuracyScore").value(88))
                .andExpect(jsonPath("$.data.scores.deliveryScore").value(74))
                .andExpect(jsonPath("$.data.questionCount").value(2))
                .andExpect(jsonPath("$.data.summary").isNotEmpty())
                .andExpect(jsonPath("$.data.weaknessTagSummary[0].tag").value("두괄식 부족"))
                .andExpect(jsonPath("$.data.weaknessTagSummary[0].count").value(3))
                // 개선 과제는 답변별 과제를 질문 순서대로 모은다 (별도 저장 없음)
                .andExpect(jsonPath("$.data.improvementTasks[0].title").value("결론부터 말하기 (PREP)"))
                .andExpect(jsonPath("$.data.improvementTasks[1].title").value("수치·사례로 근거 보강"))
                .andExpect(jsonPath("$.data.aiDisclaimer").isNotEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("전달력 미평가 리포트는 deliveryScore가 null로 반환된다 (텍스트 3축 리포트로 성립)")
    void deliveryScoreNullWhenNotEvaluated() throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, null);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scores.deliveryScore").value(nullValue()))
                .andExpect(jsonPath("$.data.scores.logicScore").value(85));
    }

    @Test
    @DisplayName("응답에 원본 이력서 참조(resumeId)와 rank 필드가 없다")
    void detailExcludesResumeIdAndRank() throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, 74);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeId").doesNotExist())
                .andExpect(jsonPath("$.data.rank").doesNotExist());
    }

    @Test
    @DisplayName("타인의 리포트 조회는 403 RP002")
    void forbiddenForOtherUser() throws Exception {
        long reportId = fixtures.evaluatedReport(OTHER_USER_ID, 74);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RP002"));
    }

    @Test
    @DisplayName("존재하지 않는 리포트 조회는 404 RP001")
    void notFoundForUnknownReport() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{reportId}", 999_999L).with(authOf(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RP001"));
    }

    @ParameterizedTest(name = "{0} 리포트 조회는 409 {1}")
    @CsvSource({
            "PENDING, RP003",     // 생성 진행 중
            "PROCESSING, RP003",  // 생성 진행 중
            "FAILED, RP004",      // 생성 실패 — 복구는 재생성의 몫
    })
    @DisplayName("완성되지 않은 리포트 조회는 409로 거부된다")
    void conflictWhenNotCompleted(ReportStatus status, String errorCode) throws Exception {
        long reportId = fixtures.reportWithStatus(USER_ID, status);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(errorCode));
    }
}
