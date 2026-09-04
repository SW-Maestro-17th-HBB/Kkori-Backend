package com.aisw.kkori.report.domain;

import com.aisw.kkori.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 면접 리포트 (docs/requirements/report/report.md).
 *
 * <p>생성 수명주기(로우 생성 → 평가 → COMPLETED)는 전부 Python Worker가 수행하며,
 * Spring은 조회·재생성 API에서만 접근한다. 세션:리포트 = 1:1이며
 * interview_session_id 유니크 제약이 이벤트 중복 소비를 방어한다.
 *
 * <p>스냅샷 컬럼(이력서 파일명)은 로우 생성 시점의 값을 복사한 것 —
 * 이후 원본이 변경·삭제되어도 목록·상세 표시는 스냅샷으로 성립한다.
 * 면접 유형·시간 필드는 없다 — 리포트는 실전(30분) 면접에만 발행되어 상수이므로.
 */
@Entity
@Table(name = "reports", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reports_interview_session_id", columnNames = "interview_session_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
public class Report extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자 (users.id). 도메인 간 결합을 낮추기 위해 연관관계 대신 id만 보관한다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "interview_session_id", nullable = false)
    private Long interviewSessionId;

    /** 면접에 사용한 이력서 id. 이력서 물리 삭제 시 NULL — 리포트는 스냅샷으로 자기완결. */
    @Column(name = "resume_id")
    private Long resumeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    /** 종합 점수 0~100 — 평가된 축의 평균. COMPLETED 전이 시 Worker가 확정. */
    @Column(name = "overall_score")
    private Integer overallScore;

    /** 전달력 점수 0~100 — 세션 오디오에서 산출. 오디오 미평가면 null(텍스트 3축 리포트로 성립). */
    @Column(name = "delivery_score")
    private Integer deliveryScore;

    /** 상세 리포트 총평 — 텍스트 분석(1단계) 산출물. */
    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "resume_file_name_snapshot", nullable = false)
    private String resumeFileNameSnapshot;

    /** 답변별 태그 빈도 상위 3개 — 스키마 정의 원천은 {@link WeaknessTagCount}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weakness_tag_summary", columnDefinition = "jsonb")
    private List<WeaknessTagCount> weaknessTagSummary;

    @Column(name = "failed_reason", columnDefinition = "text")
    private String failedReason;

    /**
     * 텍스트 분석(1단계) 완료 시각. 음성 분석 완료 시각과 둘 다 채워지면 COMPLETED로 전환된다.
     * 단, 음성 분석이 유예 시간을 넘기면 음성 없이(delivery null) 완성 처리될 수 있다.
     */
    @Column(name = "text_analyzed_at")
    private Instant textAnalyzedAt;

    /** 음성 분석(2단계) 완료 시각. */
    @Column(name = "audio_analyzed_at")
    private Instant audioAnalyzedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Builder
    private Report(Long userId, Long interviewSessionId, Long resumeId, ReportStatus status,
                   Integer overallScore, Integer deliveryScore, String summary,
                   String resumeFileNameSnapshot,
                   List<WeaknessTagCount> weaknessTagSummary, String failedReason,
                   Instant textAnalyzedAt, Instant audioAnalyzedAt, Instant completedAt) {
        this.userId = userId;
        this.interviewSessionId = interviewSessionId;
        this.resumeId = resumeId;
        this.status = status;
        this.overallScore = overallScore;
        this.deliveryScore = deliveryScore;
        this.summary = summary;
        this.resumeFileNameSnapshot = resumeFileNameSnapshot;
        this.weaknessTagSummary = weaknessTagSummary;
        this.failedReason = failedReason;
        this.textAnalyzedAt = textAnalyzedAt;
        this.audioAnalyzedAt = audioAnalyzedAt;
        this.completedAt = completedAt;
    }

    /**
     * FAILED 재생성 시작 상태로 재설정한다 (PRD §1). 반드시 생성 요청 재발행과 같은 트랜잭션에서 호출한다.
     * 텍스트 경로 산출물만 지우고 deliveryScore·audioAnalyzedAt은 보존한다 — 음성 분석은 결정적
     * 산식이라 재분석해도 같은 값이 나오므로 이전 런의 음성 결과를 재사용한다.
     * REPORT_SCORES·REPORT_FEEDBACKS 정리는 Worker가 재저장 시 수행한다(이력서 청크 정리와 동일한 분담).
     */
    public void restartForRegeneration() {
        this.status = ReportStatus.PENDING;
        this.failedReason = null;
        this.completedAt = null;
        this.textAnalyzedAt = null;
        this.overallScore = null;
        this.summary = null;
        this.weaknessTagSummary = null;
    }
}
