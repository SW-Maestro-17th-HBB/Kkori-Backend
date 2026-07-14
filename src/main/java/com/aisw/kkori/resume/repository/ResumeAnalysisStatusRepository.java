package com.aisw.kkori.resume.repository;

import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeAnalysisStatusRepository extends JpaRepository<ResumeAnalysisStatus, Long> {

    Optional<ResumeAnalysisStatus> findByResumeId(Long resumeId);
}
