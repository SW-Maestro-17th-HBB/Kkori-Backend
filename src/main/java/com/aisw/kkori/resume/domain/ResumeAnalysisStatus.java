package com.aisw.kkori.resume.domain;

import com.aisw.kkori.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 이력서 분석 진행 상태 (ERD v1.2 RESUME_ANALYSIS_STATUS, Resume와 1:1).
 *
 * <p>Resume 본체와 분리한 이유: 이 테이블은 Python AI Worker가 파이프라인 단계마다 갱신하는
 * 변경 빈도 높은 데이터라, 원본 메타데이터와 책임·갱신 주체를 격리한다.
 * Spring은 업로드 시점의 초기 상태(UPLOADED)만 기록한다.
 */
@Entity
@Table(name = "resume_analysis_status")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResumeAnalysisStatus extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false, unique = true)
    private Resume resume;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false)
    private AnalysisStatus parseStatus;

    /** 분석에 사용된 파서 버전. Worker가 기록. */
    @Column(name = "parser_version")
    private String parserVersion;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    private ResumeAnalysisStatus(Resume resume) {
        this.resume = resume;
        this.parseStatus = AnalysisStatus.UPLOADED;
        this.retryCount = 0;
    }

    /** 업로드 직후 초기 상태(UPLOADED)로 생성한다. */
    public static ResumeAnalysisStatus init(Resume resume) {
        return new ResumeAnalysisStatus(resume);
    }
}
