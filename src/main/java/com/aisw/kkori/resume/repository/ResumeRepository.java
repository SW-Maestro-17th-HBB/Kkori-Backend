package com.aisw.kkori.resume.repository;

import com.aisw.kkori.resume.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    /**
     * 같은 파일 해시의 활성 이력서 조회 (@SQLRestriction으로 soft delete 자동 제외).
     * TODO: 인증 도입 시 (userId + fileHash)로 범위를 좁힐 것 — 현재는 전역 dedup.
     */
    Optional<Resume> findFirstByFileHash(String fileHash);
}
