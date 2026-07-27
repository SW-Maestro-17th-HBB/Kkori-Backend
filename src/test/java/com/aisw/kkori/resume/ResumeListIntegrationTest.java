package com.aisw.kkori.resume;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이력서 목록 조회 통합 테스트 (docs/requirements/resume/resume.md §2 검증 기준 1:1).
 *
 * <p>created_at은 auditing이 기록하므로 정렬 검증에 필요한 시각 차이는 JdbcTemplate로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResumeListIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ResumeRepository resumeRepository;
    @Autowired ResumeAnalysisStatusRepository statusRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        statusRepository.deleteAll();
        resumeRepository.deleteAll();
    }

    @Test
    @DisplayName("본인 이력서만 createdAt 내림차순으로 반환된다")
    void list_returnsOwnResumesNewestFirst() throws Exception {
        long first = uploadedResume(USER_ID, "가장 오래된 이력서", BASE);
        long second = uploadedResume(USER_ID, "중간 이력서", BASE.plusSeconds(60));
        long third = uploadedResume(USER_ID, "최신 이력서", BASE.plusSeconds(120));
        uploadedResume(OTHER_USER_ID, "타인 이력서", BASE.plusSeconds(180));

        mockMvc.perform(get("/api/v1/resumes").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[0].resumeId").value(third))
                .andExpect(jsonPath("$.data.content[1].resumeId").value(second))
                .andExpect(jsonPath("$.data.content[2].resumeId").value(first))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("목록 항목은 UI 소비 최소 필드만 담는다 (PRD §2 — 미리보기 미포함)")
    void list_itemCarriesMinimalFieldsOnly() throws Exception {
        long resumeId = uploadedResume(USER_ID, "이력서", BASE);

        mockMvc.perform(get("/api/v1/resumes").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].resumeId").value(resumeId))
                .andExpect(jsonPath("$.data.content[0].title").value("이력서"))
                .andExpect(jsonPath("$.data.content[0].analysisStatus").value("UPLOADED"))
                .andExpect(jsonPath("$.data.content[0].createdAt").exists())
                .andExpect(jsonPath("$.data.content[0].fileSize").value(1))
                .andExpect(jsonPath("$.data.content[0].structuredData").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].originalFileName").doesNotExist());
    }

    @Test
    @DisplayName("page/size 파라미터대로 페이지네이션된다")
    void list_paginates() throws Exception {
        for (int i = 0; i < 3; i++) {
            uploadedResume(USER_ID, "이력서 " + i, BASE.plusSeconds(i * 60L));
        }

        mockMvc.perform(get("/api/v1/resumes").param("size", "2").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(get("/api/v1/resumes").param("size", "2").param("page", "1").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("status 필터 지정 시 해당 상태의 이력서만 반환된다")
    void list_filtersByStatus() throws Exception {
        embeddedResume(USER_ID, "완료 이력서", BASE);
        failedResume(USER_ID, "실패 이력서", BASE.plusSeconds(60));
        uploadedResume(USER_ID, "대기 이력서", BASE.plusSeconds(120));

        mockMvc.perform(get("/api/v1/resumes").param("status", "EMBEDDED").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("완료 이력서"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("잘못된 status 값은 400 R012로 거부된다")
    void list_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/resumes").param("status", "DONE").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("R012"));
    }

    @Test
    @DisplayName("범위를 벗어난 page/size는 400 C002로 거부된다")
    void list_invalidPageParams_return400() throws Exception {
        mockMvc.perform(get("/api/v1/resumes").param("page", "-1").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        mockMvc.perform(get("/api/v1/resumes").param("size", "0").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));

        mockMvc.perform(get("/api/v1/resumes").param("size", "101").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("FAILED 이력서도 목록에서 정상 조회된다")
    void list_includesFailedResume() throws Exception {
        failedResume(USER_ID, "실패 이력서", BASE);

        mockMvc.perform(get("/api/v1/resumes").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].analysisStatus").value("FAILED"));
    }

    @Test
    @DisplayName("삭제된(soft delete) 이력서는 목록에서 제외된다")
    void list_excludesSoftDeletedResume() throws Exception {
        uploadedResume(USER_ID, "살아있는 이력서", BASE);
        long deleted = uploadedResume(USER_ID, "삭제된 이력서", BASE.plusSeconds(60));
        jdbcTemplate.update("UPDATE resumes SET deleted_at = now() WHERE id = ?", deleted);

        mockMvc.perform(get("/api/v1/resumes").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("살아있는 이력서"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("이력서가 없으면 빈 목록이 반환된다")
    void list_empty_returnsEmptyContent() throws Exception {
        mockMvc.perform(get("/api/v1/resumes").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    // ─── 픽스처 ───

    /** 업로드 직후(UPLOADED) 이력서 생성. createdAt은 정렬 검증을 위해 지정 시각으로 덮어쓴다. */
    private long uploadedResume(long userId, String title, Instant createdAt) {
        Resume resume = resumeRepository.save(Resume.builder()
                .userId(userId)
                .title(title)
                .fileHash("hash-" + seq.incrementAndGet())
                .originalFileBucket(TestcontainersConfiguration.TEST_BUCKET)
                .originalFileKey("resumes/" + userId + "/hash.pdf")
                .originalFileName("resume.pdf")
                .fileSize(1L)
                .mimeType("application/pdf")
                .pageCount(1)
                .build());
        statusRepository.save(ResumeAnalysisStatus.init(resume));
        jdbcTemplate.update("UPDATE resumes SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), resume.getId());
        return resume.getId();
    }

    /** 분석 완료(EMBEDDED) 이력서 생성 — 상태 쓰기 주체인 Worker를 JdbcTemplate로 연기한다. */
    private long embeddedResume(long userId, String title, Instant createdAt) {
        long resumeId = uploadedResume(userId, title, createdAt);
        jdbcTemplate.update(
                "UPDATE resume_analysis_status SET parse_status = 'EMBEDDED', completed_at = now() WHERE resume_id = ?",
                resumeId);
        return resumeId;
    }

    /** 분석 실패(FAILED) 이력서 생성. */
    private long failedResume(long userId, String title, Instant createdAt) {
        long resumeId = uploadedResume(userId, title, createdAt);
        jdbcTemplate.update(
                "UPDATE resume_analysis_status SET parse_status = 'FAILED', error_message = 'LLM 타임아웃', failed_at = now() WHERE resume_id = ?",
                resumeId);
        return resumeId;
    }

    /** JwtAuthenticationFilter가 principal에 심는 것과 동일한 형태(Long)로 인증을 주입한다. */
    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }
}
