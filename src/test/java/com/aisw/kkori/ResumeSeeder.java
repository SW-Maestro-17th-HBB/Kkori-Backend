package com.aisw.kkori;

import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이력서를 원하는 분석 상태로 시딩하는 공용 테스트 픽스처.
 *
 * <p>EMBEDDED·FAILED 등은 Spring 코드로 만들 수 없으므로(상태 쓰기 주체는 Python Worker)
 * JdbcTemplate로 Worker를 연기한다 — Worker도 SQL로 갱신하므로 계약에 충실한 시뮬레이션이다.
 * resume·session 두 통합 테스트 스위트가 공유한다(사본 표류 방지).
 */
public class ResumeSeeder {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisStatusRepository statusRepository;
    private final JdbcTemplate jdbcTemplate;

    public ResumeSeeder(ResumeRepository resumeRepository, ResumeAnalysisStatusRepository statusRepository,
                        JdbcTemplate jdbcTemplate) {
        this.resumeRepository = resumeRepository;
        this.statusRepository = statusRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 업로드 직후(UPLOADED) 이력서 — (user_id, file_hash) 활성 유니크 제약과 충돌하지 않게 매번 다른 해시. */
    public long newResume(long userId) {
        Resume resume = resumeRepository.save(Resume.builder()
                .userId(userId)
                .title("이력서")
                .fileHash("hash-" + userId + "-" + System.nanoTime())
                .originalFileBucket(TestcontainersConfiguration.TEST_BUCKET)
                .originalFileKey("resumes/" + userId + "/hash.pdf")
                .originalFileName("resume.pdf")
                .fileSize(1L)
                .mimeType("application/pdf")
                .pageCount(1)
                .build());
        statusRepository.save(ResumeAnalysisStatus.init(resume));
        return resume.getId();
    }

    /**
     * 기본 구조화 데이터 — 파이프라인상 구조화(STRUCTURING)는 EMBEDDED보다 앞 단계라
     * "EMBEDDED ⇒ structured_data 존재"가 프로덕션 불변식이다. 시딩도 이를 지킨다
     * (EMBEDDED인데 데이터가 NULL인, 실제로는 불가능한 상태를 만들지 않기 위함).
     */
    private static final String DEFAULT_STRUCTURED_DATA = """
            {
              "profile": {"name": "시더", "email": "seeder@example.com"},
              "skills": [],
              "projects": [],
              "experiences": []
            }
            """;

    /** 분석 완료(EMBEDDED) 이력서 — 불변식대로 구조화 데이터도 기본값으로 함께 채운다. */
    public long embedded(long userId) {
        return embedded(userId, DEFAULT_STRUCTURED_DATA);
    }

    /** 분석 완료(EMBEDDED) 이력서 — 구조화 데이터를 지정 JSON으로 채운다(내용을 단언하는 테스트용). */
    public long embedded(long userId, String structuredDataJson) {
        long resumeId = newResume(userId);
        jdbcTemplate.update("""
                UPDATE resume_analysis_status
                SET parse_status = 'EMBEDDED', completed_at = now()
                WHERE resume_id = ?""", resumeId);
        jdbcTemplate.update("UPDATE resumes SET structured_data = ?::jsonb WHERE id = ?",
                structuredDataJson, resumeId);
        return resumeId;
    }

    /** 분석 실패(FAILED) 이력서 — 재시도 소진 후 포기한 Worker의 기록을 재현한다. */
    public long failed(long userId) {
        long resumeId = newResume(userId);
        jdbcTemplate.update("""
                UPDATE resume_analysis_status
                SET parse_status = 'FAILED', error_message = 'LLM 타임아웃', failed_at = now(), retry_count = 3
                WHERE resume_id = ?""", resumeId);
        return resumeId;
    }

    /** 분석 진행 중(EMBEDDING) 이력서. */
    public long inProgress(long userId) {
        long resumeId = newResume(userId);
        jdbcTemplate.update("""
                UPDATE resume_analysis_status
                SET parse_status = 'EMBEDDING', started_at = now()
                WHERE resume_id = ?""", resumeId);
        return resumeId;
    }
}
