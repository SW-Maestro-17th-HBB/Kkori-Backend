package com.aisw.kkori.report;

import com.aisw.kkori.report.domain.ImprovementTask;
import com.aisw.kkori.report.domain.Report;
import com.aisw.kkori.report.domain.ReportFeedback;
import com.aisw.kkori.report.domain.ReportScore;
import com.aisw.kkori.report.domain.ReportStatus;
import com.aisw.kkori.report.domain.WeaknessTagCount;
import com.aisw.kkori.report.repository.ReportFeedbackRepository;
import com.aisw.kkori.report.repository.ReportRepository;
import com.aisw.kkori.report.repository.ReportScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 리포트 테스트 픽스처 — Worker가 저장을 마친 상태를 리포지토리로 재현한다.
 * 테스트 클래스에서 {@code @Import(ReportFixtures.class)} 후 주입받아 사용한다.
 *
 * <p>생성 시각은 감사(Auditing)가 저장 시점으로 덮어쓰므로, 정렬 검증에 필요한 시각은
 * 저장 후 JdbcTemplate로 바꿔 재현한다(이력서 테스트 선례).
 */
@RequiredArgsConstructor
public class ReportFixtures {

    public static final String RESUME_FILE_NAME = "백엔드_개발자_이력서.pdf";

    /** interview_session_id 유니크 제약(세션:리포트 1:1) 충돌 방지용 일련번호. */
    private static final AtomicLong SESSION_SEQ = new AtomicLong(1000);

    private final ReportRepository reportRepository;
    private final ReportScoreRepository reportScoreRepository;
    private final ReportFeedbackRepository reportFeedbackRepository;
    private final JdbcTemplate jdbcTemplate;

    public void deleteAll() {
        reportFeedbackRepository.deleteAll();
        reportScoreRepository.deleteAll();
        reportRepository.deleteAll();
        ensureTranscriptTable();
        jdbcTemplate.update("DELETE FROM interview_transcript");
        ensureJobTable();
        jdbcTemplate.update("DELETE FROM report_generation_jobs");
    }

    /**
     * 대본 테이블 보장 — 에이전트 소유 테이블이라 JPA 엔티티가 없어 ddl-auto가 만들지 않는다.
     * 스키마 원천: Kkori-AI agent/migrations/001_interview_transcript.sql (변경 시 여기도 동기화).
     */
    private void ensureTranscriptTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interview_transcript (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    session_id BIGINT NOT NULL UNIQUE,
                    content JSONB NOT NULL,
                    deleted_at TIMESTAMPTZ
                )""");
    }

    /**
     * 생성 Job 테이블 보장 — Worker 소유 테이블이라 JPA 엔티티가 없어 ddl-auto가 만들지 않는다.
     * 스키마 원천: Kkori-AI worker/src/report/repository.py ensure_schema (변경 시 여기도 동기화).
     */
    private void ensureJobTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS report_generation_jobs (
                    id            BIGSERIAL PRIMARY KEY,
                    report_id     BIGINT NOT NULL UNIQUE,
                    retry_count   INT NOT NULL DEFAULT 0,
                    error_message TEXT,
                    requested_at  TIMESTAMPTZ NOT NULL,
                    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
    }

    /** 생성 Job 행 — Worker가 리포트와 한 트랜잭션으로 만드는 행을 재현한다. */
    public void job(long reportId, Instant requestedAt, int retryCount) {
        ensureJobTable();
        jdbcTemplate.update(
                "INSERT INTO report_generation_jobs (report_id, retry_count, requested_at) VALUES (?, ?, ?)",
                reportId, retryCount, Timestamp.from(requestedAt));
    }

    public Instant jobRequestedAt(long reportId) {
        return jdbcTemplate.queryForObject(
                "SELECT requested_at FROM report_generation_jobs WHERE report_id = ?",
                Timestamp.class, reportId).toInstant();
    }

    public int jobRetryCount(long reportId) {
        return jdbcTemplate.queryForObject(
                "SELECT retry_count FROM report_generation_jobs WHERE report_id = ?",
                Integer.class, reportId);
    }

    /** 세션 대본 저장 — Worker·타임라인이 읽는 발화 배열 JSON을 그대로 넣는다. */
    public void transcript(long sessionId, String contentJson) {
        ensureTranscriptTable();
        jdbcTemplate.update(
                "INSERT INTO interview_transcript (session_id, content) VALUES (?, ?::jsonb)",
                sessionId, contentJson);
    }

    /** 리포트가 물고 있는 세션 ID — 대본 픽스처를 같은 세션에 심을 때 사용. */
    public long sessionIdOf(long reportId) {
        return reportRepository.findById(reportId).orElseThrow().getInterviewSessionId();
    }

    /** 기본 리포트 행 — COMPLETED면 점수·태그 요약·완료 시각까지 채운다. createdAt을 지정 시각으로 맞춘다. */
    public long report(long userId, ReportStatus status, Integer overallScore, Instant createdAt) {
        boolean completed = status == ReportStatus.COMPLETED;
        Report report = reportRepository.save(Report.builder()
                .userId(userId)
                .interviewSessionId(SESSION_SEQ.incrementAndGet())
                .resumeId(10L)
                .status(status)
                .overallScore(completed ? overallScore : null)
                .summary(completed ? "총평" : null)
                .resumeFileNameSnapshot(RESUME_FILE_NAME)
                .weaknessTagSummary(completed ? List.of(new WeaknessTagCount("두괄식 부족", 2)) : null)
                .failedReason(status == ReportStatus.FAILED ? "재전달 임계 초과" : null)
                .completedAt(completed ? createdAt.plusSeconds(180) : null)
                .build());
        jdbcTemplate.update("UPDATE reports SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), report.getId());
        return report.getId();
    }

    public long completedReport(long userId, Integer overallScore, Instant createdAt) {
        return report(userId, ReportStatus.COMPLETED, overallScore, createdAt);
    }

    public long reportWithStatus(long userId, ReportStatus status) {
        return report(userId, status, null, Instant.now());
    }

    /**
     * 재생성 검증용 FAILED 리포트 — 텍스트 경로 산출물과 음성 산출물을 전부 채워,
     * 재생성이 텍스트 경로만 지우고 음성 결과는 보존하는지 선별 검증할 수 있게 한다.
     */
    public long failedReportWithPreviousRun(long userId, Integer deliveryScore) {
        Report report = reportRepository.save(Report.builder()
                .userId(userId)
                .interviewSessionId(SESSION_SEQ.incrementAndGet())
                .resumeId(10L)
                .status(ReportStatus.FAILED)
                .overallScore(55)
                .deliveryScore(deliveryScore)
                .summary("이전 런 총평")
                .resumeFileNameSnapshot(RESUME_FILE_NAME)
                .weaknessTagSummary(List.of(new WeaknessTagCount("두괄식 부족", 1)))
                .failedReason("재전달 임계 초과")
                .textAnalyzedAt(Instant.now())
                .audioAnalyzedAt(deliveryScore == null ? null : Instant.now())
                .completedAt(Instant.now())
                .build());
        return report.getId();
    }

    /** 상세 조회용 완전체 — COMPLETED 리포트 + 텍스트 3축 점수 + 답변별 피드백 2건(질문 1·2). */
    public long evaluatedReport(long userId, Integer deliveryScore) {
        Report report = reportRepository.save(Report.builder()
                .userId(userId)
                .interviewSessionId(SESSION_SEQ.incrementAndGet())
                .resumeId(10L)
                .status(ReportStatus.COMPLETED)
                .overallScore(82)
                .deliveryScore(deliveryScore)
                .summary("전반적으로 기술 근거가 탄탄하지만 결론을 먼저 말하는 구성이 부족합니다.")
                .resumeFileNameSnapshot(RESUME_FILE_NAME)
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
}
