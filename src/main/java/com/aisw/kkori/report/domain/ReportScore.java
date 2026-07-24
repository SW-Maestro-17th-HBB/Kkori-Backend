package com.aisw.kkori.report.domain;

import com.aisw.kkori.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션 단위 텍스트 3축 영역 점수 (리포트와 1:1).
 *
 * <p>각 축 점수 = 답변별 해당 축 점수의 평균(반올림) — 산식의 정의 원천은
 * docs/requirements/report/report.md §1 점수 체계. 전달력·종합 점수는 세션 단위
 * 단일 값이라 REPORTS 컬럼에 있다(2단계 음성 분석이 언제 끝나도 갱신 가능하도록).
 */
@Entity
@Table(name = "report_scores", uniqueConstraints = {
        @UniqueConstraint(name = "uk_report_scores_report_id", columnNames = "report_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportScore extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    /** 논리성 0~100 */
    @Column(name = "logic_score", nullable = false)
    private Integer logicScore;

    /** 구체성 0~100 */
    @Column(name = "specificity_score", nullable = false)
    private Integer specificityScore;

    /** 기술 정확성 0~100 */
    @Column(name = "technical_accuracy_score", nullable = false)
    private Integer technicalAccuracyScore;

    @Builder
    private ReportScore(Long reportId, Integer logicScore, Integer specificityScore,
                        Integer technicalAccuracyScore) {
        this.reportId = reportId;
        this.logicScore = logicScore;
        this.specificityScore = specificityScore;
        this.technicalAccuracyScore = technicalAccuracyScore;
    }
}
