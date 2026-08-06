package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.dto.ReportGenerationRequestedMessage;
import com.aisw.kkori.report.repository.ReportRepository;
import com.aisw.kkori.report.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static com.aisw.kkori.ConcurrencyTestSupport.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리포트 재생성 통합 테스트 (docs/requirements/report/report.md §1 검증 기준 중 Spring 소관).
 *
 * <p>Worker의 재수행(텍스트 분석·산출물 대체)은 Worker 레포 소관 — 여기서는 재생성 API가
 * 만드는 상태(초기화·PENDING 전환·생성 요청 재발행·Job 갱신)와 거부 규칙을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReportFixtures.class})
class ReportRegenerateIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Autowired MockMvc mockMvc;
    @Autowired ReportFixtures fixtures;
    @Autowired ReportRepository reportRepository;
    @Autowired ReportService reportService;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        fixtures.deleteAll();
        redisTemplate.delete(ReportGenerationRequestedMessage.STREAM_KEY);
    }

    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private List<MapRecord<String, Object, Object>> streamRecords() {
        return redisTemplate.opsForStream()
                .range(ReportGenerationRequestedMessage.STREAM_KEY, Range.unbounded());
    }

    // ─── 검증 기준 (PRD §1) ───

    @Test
    @DisplayName("FAILED 리포트 재생성 시 PENDING으로 되돌아가고 생성 요청이 재발행된다")
    void regenerateResetsToPendingAndPublishes() throws Exception {
        long reportId = fixtures.failedReportWithPreviousRun(USER_ID, 70);
        fixtures.job(reportId, Instant.now().minusSeconds(3600), 3);

        mockMvc.perform(post("/api/v1/reports/{reportId}/retry", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value(reportId))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // 생성 요청 재발행 — 계약대로 sessionId 하나만 실린다
        List<MapRecord<String, Object, Object>> records = streamRecords();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue())
                .hasSize(1)
                .containsEntry("sessionId", String.valueOf(fixtures.sessionIdOf(reportId)));
    }

    @Test
    @DisplayName("재생성은 텍스트 경로 산출물만 초기화하고 이전 런의 음성 결과는 보존한다")
    void regenerateClearsTextPathButPreservesAudio() throws Exception {
        long reportId = fixtures.failedReportWithPreviousRun(USER_ID, 70);
        fixtures.job(reportId, Instant.now().minusSeconds(3600), 3);
        Instant audioAnalyzedAt = reportRepository.findById(reportId).orElseThrow().getAudioAnalyzedAt();

        mockMvc.perform(post("/api/v1/reports/{reportId}/retry", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk());

        Report report = reportRepository.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(report.getFailedReason()).isNull();
        assertThat(report.getCompletedAt()).isNull();
        assertThat(report.getTextAnalyzedAt()).isNull();
        assertThat(report.getOverallScore()).isNull();
        assertThat(report.getSummary()).isNull();
        assertThat(report.getWeaknessTagSummary()).isNull();
        // 음성 산출물 보존 — 음성 분석은 결정적 산식이라 재분석 불요, 재생성 완료 시 이 값과 합쳐진다
        assertThat(report.getDeliveryScore()).isEqualTo(70);
        assertThat(report.getAudioAnalyzedAt()).isEqualTo(audioAnalyzedAt);
    }

    @Test
    @DisplayName("재생성 시 Job의 requested_at은 갱신되고 retry_count는 Worker 소관이라 그대로다")
    void regenerateUpdatesJobRequestedAtOnly() throws Exception {
        long reportId = fixtures.failedReportWithPreviousRun(USER_ID, null);
        Instant previousRequestedAt = Instant.now().minusSeconds(3600);
        fixtures.job(reportId, previousRequestedAt, 3);

        mockMvc.perform(post("/api/v1/reports/{reportId}/retry", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk());

        assertThat(fixtures.jobRequestedAt(reportId)).isAfter(previousRequestedAt);
        assertThat(fixtures.jobRetryCount(reportId)).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0} 리포트는 {1}로 거부된다")
    @CsvSource({
            "PENDING, RP003",
            "PROCESSING, RP003",
            "COMPLETED, RP005",
    })
    @DisplayName("FAILED가 아닌 리포트의 재생성 요청은 409로 거부되고 아무것도 발행되지 않는다")
    void nonFailedReportIsRejected(ReportStatus reportStatus, String errorCode) throws Exception {
        long reportId = fixtures.reportWithStatus(USER_ID, reportStatus);

        mockMvc.perform(post("/api/v1/reports/{reportId}/retry", reportId).with(authOf(USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(errorCode));

        assertThat(streamRecords()).isEmpty();
        assertThat(reportRepository.findById(reportId).orElseThrow().getStatus()).isEqualTo(reportStatus);
    }

    @Test
    @DisplayName("존재하지 않는 리포트의 재생성 요청은 404(RP001)다")
    void missingReportIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/reports/{reportId}/retry", 999_999).with(authOf(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RP001"));

        assertThat(streamRecords()).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 리포트 재생성 요청은 403(RP002)이고 상태가 바뀌지 않는다")
    void foreignReportIsForbidden() throws Exception {
        long reportId = fixtures.failedReportWithPreviousRun(OTHER_USER_ID, null);

        mockMvc.perform(post("/api/v1/reports/{reportId}/retry", reportId).with(authOf(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RP002"));

        assertThat(streamRecords()).isEmpty();
        assertThat(reportRepository.findById(reportId).orElseThrow().getStatus())
                .isEqualTo(ReportStatus.FAILED);
    }

    @Test
    @DisplayName("처리 트랜잭션 실패 시 500(RP006)이고 리포트는 FAILED로 유지된다 (다시 시도 가능)")
    void processingFailureKeepsFailedState() throws Exception {
        // Job 행 부재 = 계약상 불가능한 상태를 재현해 처리 실패 경로를 트리거한다
        long reportId = fixtures.failedReportWithPreviousRun(USER_ID, null);

        mockMvc.perform(post("/api/v1/reports/{reportId}/retry", reportId).with(authOf(USER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("RP006"));

        // 전부 롤백 — 초기화·PENDING 전환이 남지 않고, 발행도 일어나지 않는다
        Report report = reportRepository.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(report.getFailedReason()).isNotNull();
        assertThat(streamRecords()).isEmpty();
    }

    @Test
    @DisplayName("같은 리포트에 동시 재생성 요청 두 건은 한 건만 처리되고 나머지는 409로 거부된다")
    void concurrentRegenerateProcessesExactlyOnce() throws Exception {
        long reportId = fixtures.failedReportWithPreviousRun(USER_ID, null);
        fixtures.job(reportId, Instant.now().minusSeconds(3600), 0);
        AtomicReference<ErrorCode> firstError = new AtomicReference<>();
        AtomicReference<ErrorCode> secondError = new AtomicReference<>();

        runConcurrently(
                () -> {
                    try {
                        reportService.regenerate(USER_ID, reportId);
                    } catch (BusinessException e) {
                        firstError.set(e.getErrorCode());
                    }
                },
                () -> {
                    try {
                        reportService.regenerate(USER_ID, reportId);
                    } catch (BusinessException e) {
                        secondError.set(e.getErrorCode());
                    }
                });

        // 행 잠금이 검사~커밋을 직렬화 — 늦은 쪽은 PENDING을 보고 RP003으로 거부된다
        List<ErrorCode> errors = List.of(firstError, secondError).stream()
                .map(AtomicReference::get)
                .filter(Objects::nonNull)
                .toList();
        assertThat(errors).containsExactly(ErrorCode.REPORT_GENERATION_IN_PROGRESS);
        assertThat(reportRepository.findById(reportId).orElseThrow().getStatus())
                .isEqualTo(ReportStatus.PENDING);
        assertThat(streamRecords()).hasSize(1);
    }
}
