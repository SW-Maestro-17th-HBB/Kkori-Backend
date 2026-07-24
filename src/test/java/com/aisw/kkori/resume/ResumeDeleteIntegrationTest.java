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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이력서 삭제 통합 테스트 (docs/requirements/resume/resume.md §5 검증 기준 중 MVP 범위).
 *
 * <p>물리 삭제(S3·청크·임베딩 제거, 공유 objectKey 참조 확인)는 후속 배치 소관이라 여기서 다루지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResumeDeleteIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

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
    @DisplayName("삭제하면 200이 반환되고 deleted_at이 기록된다 (soft delete)")
    void delete_marksDeletedAt() throws Exception {
        long resumeId = newResume(USER_ID);

        mockMvc.perform(delete("/api/v1/resumes/{resumeId}", resumeId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        // @SQLRestriction이 걸린 JPA 조회 대신 raw SQL로 soft delete 기록을 확인한다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM resumes WHERE id = ?", java.sql.Timestamp.class, resumeId))
                .isNotNull();
    }

    @Test
    @DisplayName("삭제된 이력서는 목록 조회에서 더 이상 노출되지 않는다")
    void delete_removesFromList() throws Exception {
        long resumeId = newResume(USER_ID);

        mockMvc.perform(delete("/api/v1/resumes/{resumeId}", resumeId).with(authOf(USER_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/resumes").with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("타인의 이력서 삭제는 403 R009로 거부된다")
    void delete_othersResume_returns403() throws Exception {
        long resumeId = newResume(OTHER_USER_ID);

        mockMvc.perform(delete("/api/v1/resumes/{resumeId}", resumeId).with(authOf(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("R009"));
    }

    @Test
    @DisplayName("존재하지 않는 이력서 삭제는 404 R008이다")
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/resumes/{resumeId}", 999_999L).with(authOf(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("R008"));
    }

    @Test
    @DisplayName("이미 삭제된 이력서를 다시 삭제하면 404 R008이다 (@SQLRestriction으로 미노출)")
    void delete_alreadyDeleted_returns404() throws Exception {
        long resumeId = newResume(USER_ID);
        mockMvc.perform(delete("/api/v1/resumes/{resumeId}", resumeId).with(authOf(USER_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/resumes/{resumeId}", resumeId).with(authOf(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("R008"));
    }

    @Test
    @DisplayName("FAILED 상태 이력서도 삭제할 수 있다")
    void delete_failedResume_succeeds() throws Exception {
        long resumeId = newResume(USER_ID);
        jdbcTemplate.update(
                "UPDATE resume_analysis_status SET parse_status = 'FAILED', error_message = 'LLM 타임아웃', failed_at = now() WHERE resume_id = ?",
                resumeId);

        mockMvc.perform(delete("/api/v1/resumes/{resumeId}", resumeId).with(authOf(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("삭제 후 같은 파일을 다시 업로드해도 중복으로 판정되지 않는다 (부분 유니크 인덱스)")
    void delete_allowsReuploadOfSameFile() throws Exception {
        // 중복 판정은 (userId + fileHash) 활성 레코드 기준 — soft delete 후엔 같은 해시로 새 레코드 생성 가능해야 한다
        long resumeId = newResumeWithHash(USER_ID, "same-hash");
        mockMvc.perform(delete("/api/v1/resumes/{resumeId}", resumeId).with(authOf(USER_ID)))
                .andExpect(status().isOk());

        long recreated = newResumeWithHash(USER_ID, "same-hash");
        assertThat(recreated).isNotEqualTo(resumeId);
    }

    // ─── 픽스처 ───

    private long newResume(long userId) {
        return newResumeWithHash(userId, "hash-" + seq.incrementAndGet());
    }

    private long newResumeWithHash(long userId, String fileHash) {
        Resume resume = resumeRepository.save(Resume.builder()
                .userId(userId)
                .title("이력서")
                .fileHash(fileHash)
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

    /** JwtAuthenticationFilter가 principal에 심는 것과 동일한 형태(Long)로 인증을 주입한다. */
    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }
}
