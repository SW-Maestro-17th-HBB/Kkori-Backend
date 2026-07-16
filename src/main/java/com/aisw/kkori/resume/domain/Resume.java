package com.aisw.kkori.resume.domain;

import com.aisw.kkori.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/**
 * 업로드된 이력서 원본 메타데이터 (ERD v1.2 RESUMES).
 *
 * <p>파일 실체는 S3에 저장하고 여기엔 위치·메타데이터만 둔다.
 */
@Entity
@Table(name = "resumes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
public class Resume extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자 (users.id). 도메인 간 결합을 낮추기 위해 연관관계 대신 id만 보관한다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 표시 이름. 업로드 시 미지정이면 원본 파일명을 사용한다. */
    @Column(nullable = false)
    private String title;

    /** 파일 바이너리의 SHA-256 — 동일 파일 판단·해시 기반 objectKey의 근거. */
    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "original_file_bucket", nullable = false)
    private String originalFileBucket;

    @Column(name = "original_file_key", nullable = false)
    private String originalFileKey;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "page_count", nullable = false)
    private Integer pageCount;

    /** AI 구조화 결과 — Worker가 채우므로 업로드 직후엔 null. 스키마 정의 원천은 {@link StructuredData}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_data", columnDefinition = "jsonb")
    private StructuredData structuredData;

    /** 파싱 결과 수정(PRD §4). 저장만 한다 — 재색인은 별도 재분석 요청으로만 일어난다. */
    public void updateStructuredData(StructuredData structuredData) {
        this.structuredData = structuredData;
    }

    @Builder
    private Resume(Long userId, String title, String fileHash, String originalFileBucket,
                   String originalFileKey, String originalFileName, Long fileSize, String mimeType,
                   Integer pageCount) {
        this.userId = userId;
        this.title = title;
        this.fileHash = fileHash;
        this.originalFileBucket = originalFileBucket;
        this.originalFileKey = originalFileKey;
        this.originalFileName = originalFileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.pageCount = pageCount;
    }
}
