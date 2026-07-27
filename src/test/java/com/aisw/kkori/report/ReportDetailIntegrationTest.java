package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.report.domain.ImprovementTask;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportFeedback;
import com.aisw.kkori.report.domain.ReportScore;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.domain.WeaknessTagCount;
import com.aisw.kkori.report.repository.ReportFeedbackRepository;
import com.aisw.kkori.report.repository.ReportRepository;
import com.aisw.kkori.report.repository.ReportScoreRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리포트 상세 조회 통합 테스트 (docs/requirements/report/report.md §3 검증 기준 1:1).
 *
 * <p>리포트 데이터는 전부 Worker가 쓰므로(생성·평가·상태 전이), 테스트는 리포지토리로
 * Worker가 저장을 마친 상태를 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportDetailIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    /** interview_session_id 유니크 제약(세션:리포트 1:1) 충돌 방지용 일련번호. */
    private static final AtomicLong SESSION_SEQ = new AtomicLong(1000);

    @Autowired MockMvc mockMvc;
    @Autowired ReportRepository reportRepository;
    @Autowired ReportScoreRepository reportScoreRepository;
    @Autowired ReportFeedbackRepository reportFeedbackRepository;

    @BeforeEach
    void setUp() {
        reportFeedbackRepository.deleteAll();
        reportScoreRepository.deleteAll();
        reportRepository.deleteAll();
    }

    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    // ─── 픽스처 ───

    private long completedReport(long userId, Integer deliveryScore) {
        Report report = reportRepository.save(Report.builder()
                .userId(userId)
                .interviewSessionId(SESSION_SEQ.incrementAndGet())
                .resumeId(10L)
                .status(ReportStatus.COMPLETED)
                .overallScore(82)
                .deliveryScore(deliveryScore)
                .summary("전반적으로 기술 근거가 탄탄하지만 결론을 먼저 말하는 구성이 부족합니다.")
                .resumeFileNameSnapshot("백엔드_개발자_이력서.pdf")
                .weaknessTagSummary(List.of(
                        new WeaknessTagCount("두괄식 부족", 3),
                        new WeaknessTagCount("근거 부족", 2)))
                .textAnalyzedAt(Instant.now())
                .audioAnalyzedAt(deliveryScore == null ? null : Instant.now())
                .completedAt(Instant.now())
                .build());
        reportScoreRepository.save(ReportScore.builder()
                .reportId(report.getId())
                .logicScore(85)
                .specificityScore(72)
                .technicalAccuracyScore(88)
                .build());
        reportFeedbackRepository.save(ReportFeedback.builder()
                .reportId(report.getId())
                .questionNumber(1)
                .logicScore(80).specificityScore(75).technicalAccuracyScore(82)
                .feedback("두괄식으로 시작하면 더 좋아요")
                .weaknessTags(List.of("두괄식 부족"))
                .improvementTasks(List.of(new ImprovementTask("결론부터 말하기 (PREP)", "답변 첫 문장에 핵심 결론 배치")))
                .build());
        reportFeedbackRepository.save(ReportFeedback.builder()
                .reportId(report.getId())
                .questionNumber(2)
                .logicScore(70).specificityScore(68).technicalAccuracyScore(80)
                .feedback("사례가 부족합니다")
                .weaknessTags(List.of("근거 부족"))
                .improvementTasks(List.of(new ImprovementTask("수치·사례로 근거 보강", "'왜'에 정량적 근거 1개 이상")))
                .build());
        return report.getId();
    }

    private long reportWithStatus(long userId, ReportStatus status) {
        Report report = reportRepository.save(Report.builder()
                .userId(userId)
                .interviewSessionId(SESSION_SEQ.incrementAndGet())
                .resumeId(10L)
                .status(status)
                .resumeFileNameSnapshot("백엔드_개발자_이력서.pdf")
                .failedReason(status == ReportStatus.FAILED ? "재전달 임계 초과" : null)
                .build());
        return report.getId();
    }

    // ─── 검증 기준 (PRD §3) ───

    @Test
    @DisplayName("COMPLETED 리포트 상세에 총평·축별 점수·overall·질문 수·약점 태그 요약·개선 과제·aiDisclaimer가 포함된다")
    void detailContainsAllFields() throws Exception {
        long reportId = completedReport(USER_ID, 74);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reportId").value(reportId))
                .andExpect(jsonPath("$.data.resumeFileName").value("백엔드_개발자_이력서.pdf"))
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
        long reportId = completedReport(USER_ID, null);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scores.deliveryScore").value(nullValue()))
                .andExpect(jsonPath("$.data.scores.logicScore").value(85));
    }

    @Test
    @DisplayName("응답에 원본 이력서 참조(resumeId)와 rank 필드가 없다")
    void detailExcludesResumeIdAndRank() throws Exception {
        long reportId = completedReport(USER_ID, 74);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeId").doesNotExist())
                .andExpect(jsonPath("$.data.rank").doesNotExist());
    }

    @Test
    @DisplayName("타인의 리포트 조회는 403 RP002")
    void forbiddenForOtherUser() throws Exception {
        long reportId = completedReport(OTHER_USER_ID, 74);

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
        long reportId = reportWithStatus(USER_ID, status);

        mockMvc.perform(get("/api/v1/reports/{reportId}", reportId).with(authOf(USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(errorCode));
    }
}
