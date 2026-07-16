package com.aisw.kkori.resume.dto;

import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;

import java.time.Instant;

/**
 * 이력서 업로드 응답 (API 명세 4.1).
 *
 * <p>{@code duplicated}: 같은 파일의 활성 이력서가 이미 있어 새로 만들지 않고
 * 기존 정보를 반환했음을 나타낸다 (docs/requirements/resume/resume.md §1 중복 규칙).
 */
public record ResumeUploadResponse(
        Long resumeId,
        String title,
        String originalFileName,
        Long fileSize,
        String mimeType,
        Integer pageCount,
        AnalysisStatus analysisStatus,
        Instant createdAt,
        boolean duplicated
) {

    public static ResumeUploadResponse created(Resume resume, AnalysisStatus analysisStatus) {
        return from(resume, analysisStatus, false);
    }

    public static ResumeUploadResponse duplicated(Resume resume, AnalysisStatus analysisStatus) {
        return from(resume, analysisStatus, true);
    }

    private static ResumeUploadResponse from(Resume resume, AnalysisStatus analysisStatus, boolean duplicated) {
        return new ResumeUploadResponse(
                resume.getId(),
                resume.getTitle(),
                resume.getOriginalFileName(),
                resume.getFileSize(),
                resume.getMimeType(),
                resume.getPageCount(),
                analysisStatus,
                resume.getCreatedAt(),
                duplicated
        );
    }
}
