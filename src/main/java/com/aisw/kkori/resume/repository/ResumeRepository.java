package com.aisw.kkori.resume.repository;

import com.aisw.kkori.resume.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    /**
     * 같은 사용자의 같은 파일 해시 활성 이력서 조회 (@SQLRestriction으로 soft delete 자동 제외).
     * 반드시 userId 스코프로 조회 — 해시만으로 조회하면 타 사용자의 이력서가 반환되는 정보 누출.
     */
    Optional<Resume> findFirstByUserIdAndFileHash(Long userId, String fileHash);
}
