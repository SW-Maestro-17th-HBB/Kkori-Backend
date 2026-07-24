package com.aisw.kkori.report.domain;

import com.aisw.kkori.global.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 답변 단위 평가 (질문당 1건 — 평가·조인 단위는 questionNumber).
 *
 * <p>Worker가 텍스트 분석(1단계)에서 저장한다. 전달력은 세션 단위 측정이라
 * 여기에는 delivery 점수 컬럼이 없다(텍스트 3축만).
 * 타임라인 API가 대본의 같은 questionNumber와 결합해 반환한다.
 */
@Entity
@Table(name = "report_feedbacks", indexes = {
        @Index(name = "idx_report_feedbacks_report_id", columnList = "report_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportFeedback extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    /** 대본의 질문-답변 쌍 순번(꼬리 포함 연속, 유일) — 대본·평가 결합의 키. */
    @Column(name = "question_number", nullable = false)
    private Integer questionNumber;

    @Column(name = "logic_score", nullable = false)
    private Integer logicScore;

    @Column(name = "specificity_score", nullable = false)
    private Integer specificityScore;

    @Column(name = "technical_accuracy_score", nullable = false)
    private Integer technicalAccuracyScore;

    /** 답변에 대한 종합 피드백. */
    @Column(nullable = false, columnDefinition = "text")
    private String feedback;

    /** 약점 태그 코드 목록 — 고정 어휘집의 코드 문자열(어휘집은 Worker PRD 소관). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weakness_tags", columnDefinition = "jsonb")
    private List<String> weaknessTags;

    /** 답변별 개선 과제 — 스키마 정의 원천은 {@link ImprovementTask}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "improvement_tasks", columnDefinition = "jsonb")
    private List<ImprovementTask> improvementTasks;

    /** 평가에 인용한 이력서 근거 — Worker 소관의 자유 구조(백엔드는 불투명 취급, 응답 미노출). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resume_context", columnDefinition = "jsonb")
    private JsonNode resumeContext;

    @Builder
    private ReportFeedback(Long reportId, Integer questionNumber, Integer logicScore,
                           Integer specificityScore, Integer technicalAccuracyScore, String feedback,
                           List<String> weaknessTags, List<ImprovementTask> improvementTasks,
                           JsonNode resumeContext) {
        this.reportId = reportId;
        this.questionNumber = questionNumber;
        this.logicScore = logicScore;
        this.specificityScore = specificityScore;
        this.technicalAccuracyScore = technicalAccuracyScore;
        this.feedback = feedback;
        this.weaknessTags = weaknessTags;
        this.improvementTasks = improvementTasks;
        this.resumeContext = resumeContext;
    }
}
