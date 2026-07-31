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
