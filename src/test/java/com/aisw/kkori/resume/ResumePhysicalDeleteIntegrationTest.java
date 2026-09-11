package com.aisw.kkori.resume;

import com.aisw.kkori.ResumeSeeder;
import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.resume.config.ResumePhysicalDeleteProperties;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.resume.service.ResumePhysicalDeleteScheduler;
import com.aisw.kkori.resume.service.ResumePhysicalDeleteService;
import com.aisw.kkori.resume.service.ResumeUploadService;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repository.UserRepository;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * 개별 이력서 물리 삭제 배치 (PRD {@code docs/requirements/user/deletion.md} 기능 5 검증 기준).
 *
 * <p>배치를 고정 Clock으로 직접 구성해 지연·상한 조건을 결정적으로 재현하고, S3(MinIO)·청크·행 삭제는 실물로 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResumePhysicalDeleteIntegrationTest {

    private static final String BUCKET = TestcontainersConfiguration.TEST_BUCKET;
    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration DELAY = Duration.ofMinutes(10);
    private static final Duration CEILING = DELAY.multipliedBy(6);

    @Autowired ResumeRepository resumeRepository;
    @Autowired ResumeAnalysisStatusRepository statusRepository;
    @Autowired UserRepository userRepository;
    @Autowired ResumeRepositoryService resumeRepositoryService;
    @Autowired UserRepositoryService userRepositoryService;
    @Autowired ResumeUploadService resumeUploadService;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 업로드 경로의 잠금 전 존재 확인 직후에 배치를 끼워 넣는 경합 재현용 — 그 외 호출은 실물이다. */
    @MockitoSpyBean S3Template s3Template;
    @Autowired TransactionTemplate transactionTemplate;

    private ResumeSeeder seeder;

    @BeforeEach
    void setUp() {
        if (!s3Template.bucketExists(BUCKET)) {
            s3Template.createBucket(BUCKET);
        }
        seeder = new ResumeSeeder(resumeRepository, statusRepository, jdbcTemplate);
        seeder.ensureChunkTable();
        jdbcTemplate.update("DELETE FROM resume_chunks");
        statusRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM resumes"); // soft delete 행 포함
        jdbcTemplate.update("DELETE FROM interview_session");
        jdbcTemplate.update("DELETE FROM users");
    }

    private ResumePhysicalDeleteService serviceAt(Instant now) {
        return new ResumePhysicalDeleteService(resumeRepositoryService, userRepositoryService, s3Template,
                new ResumePhysicalDeleteProperties(Duration.ofMinutes(10), DELAY), transactionTemplate,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private long user(String providerId) {
        return userRepository.save(User.create(providerId, providerId + "@example.com", "테스터")).getId();
    }

    private String keyOf(long userId, String hash) {
        return "resumes/" + userId + "/" + hash + ".pdf";
    }

    /** 이력서 + S3 객체 + 청크 1건. 분석 상태는 지정값. */
    private long resume(long userId, String hash, AnalysisStatus status) {
        String key = keyOf(userId, hash);
        if (!s3Template.objectExists(BUCKET, key)) {
            s3Template.upload(BUCKET, key, new ByteArrayInputStream("pdf".getBytes()), ObjectMetadata.builder().build());
        }
        Resume resume = resumeRepository.save(Resume.builder()
                .userId(userId).title("이력서").fileHash(hash)
                .originalFileBucket(BUCKET).originalFileKey(key).originalFileName("resume.pdf")
                .fileSize(1L).mimeType("application/pdf").pageCount(1).build());
        statusRepository.save(ResumeAnalysisStatus.init(resume));
        jdbcTemplate.update("UPDATE resume_analysis_status SET parse_status = ? WHERE resume_id = ?",
                status.name(), resume.getId());
        seeder.chunk(resume.getId());
        return resume.getId();
    }

    private void softDeletedAt(long resumeId, Instant deletedAt) {
        jdbcTemplate.update("UPDATE resumes SET deleted_at = ? WHERE id = ?", Timestamp.from(deletedAt), resumeId);
    }

    private boolean rowExists(long resumeId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM resumes WHERE id = ?", Integer.class, resumeId);
        return count != null && count > 0;
    }

    private boolean statusExists(long resumeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM resume_analysis_status WHERE resume_id = ?", Integer.class, resumeId);
        return count != null && count > 0;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"EMBEDDED", "FAILED"})
    @DisplayName("지연 경과 + 분석 terminal(EMBEDDED·FAILED) 이력서는 S3 객체·청크·분석 상태·행이 물리 삭제된다")
    void deletesTerminalResumeAfterDelay(AnalysisStatus status) {
        long userId = user("kakao-pd-1-" + status.name().toLowerCase());
        long resumeId = resume(userId, "h1", status);
        softDeletedAt(resumeId, NOW.minus(DELAY)); // 경계 정각 = 경과

        serviceAt(NOW).runCycle();

        assertThat(s3Template.objectExists(BUCKET, keyOf(userId, "h1"))).isFalse();
        assertThat(seeder.chunkCount(resumeId)).isZero();
        assertThat(statusExists(resumeId)).isFalse();
        assertThat(rowExists(resumeId)).isFalse();
    }

    @Test
    @DisplayName("지연 미경과 이력서는 삭제되지 않는다")
    void keepsResumeWithinDelay() {
        long userId = user("kakao-pd-2");
        long resumeId = resume(userId, "h2", AnalysisStatus.EMBEDDED);
        softDeletedAt(resumeId, NOW.minus(DELAY).plusSeconds(1));

        serviceAt(NOW).runCycle();

        assertThat(rowExists(resumeId)).isTrue();
        assertThat(s3Template.objectExists(BUCKET, keyOf(userId, "h2"))).isTrue();
        assertThat(seeder.chunkCount(resumeId)).isEqualTo(1);
    }

    @ParameterizedTest(name = "soft delete 후 {0}분 → 삭제 {1}")
    @CsvSource({"30, false", "60, true"})
    @DisplayName("분석 진행 중 이력서는 보류 상한(지연×6) 전에는 보류되고 상한이 지나면 삭제된다")
    void inProgressResumeWaitsUntilCeiling(long minutesAgo, boolean deleted) {
        long userId = user("kakao-pd-3-" + minutesAgo);
        long resumeId = resume(userId, "h3", AnalysisStatus.PARSING);
        softDeletedAt(resumeId, NOW.minus(Duration.ofMinutes(minutesAgo)));

        serviceAt(NOW).runCycle();

        assertThat(rowExists(resumeId)).isEqualTo(!deleted);
        assertThat(s3Template.objectExists(BUCKET, keyOf(userId, "h3"))).isEqualTo(!deleted);
        assertThat(CEILING).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("같은 파일이 재업로드되어 활성 이력서가 같은 키를 공유하면 S3 객체는 남고 soft delete 행·청크만 삭제된다")
    void keepsSharedObjectWhenActiveDuplicateExists() {
        long userId = user("kakao-pd-4");
        long deleted = resume(userId, "h4", AnalysisStatus.EMBEDDED);
        softDeletedAt(deleted, NOW.minus(CEILING));
        long active = resume(userId, "h4", AnalysisStatus.EMBEDDED); // 재업로드 — 같은 (user_id, file_hash) 활성 행

        serviceAt(NOW).runCycle();

        assertThat(s3Template.objectExists(BUCKET, keyOf(userId, "h4"))).isTrue();
        assertThat(rowExists(deleted)).isFalse();
        assertThat(seeder.chunkCount(deleted)).isZero();
        assertThat(rowExists(active)).isTrue();
        assertThat(seeder.chunkCount(active)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키를 공유하는 soft delete 행 두 건은 모두 처리되고 S3 객체는 한 번만 삭제된다(두 번째는 no-op)")
    void deletesBothSoftDeletedRowsSharingKey() {
        long userId = user("kakao-pd-5");
        long first = resume(userId, "h5", AnalysisStatus.EMBEDDED);
        softDeletedAt(first, NOW.minus(CEILING));
        long second = resume(userId, "h5", AnalysisStatus.EMBEDDED);
        softDeletedAt(second, NOW.minus(DELAY));

        serviceAt(NOW).runCycle();

        assertThat(s3Template.objectExists(BUCKET, keyOf(userId, "h5"))).isFalse();
        assertThat(rowExists(first)).isFalse();
        assertThat(rowExists(second)).isFalse();
    }

    @Test
    @DisplayName("S3 삭제 실패(존재하지 않는 버킷) 시 행·청크가 남고, 원인 해소 후 다음 회차에 삭제된다")
    void s3FailureKeepsRowUntilRetry() {
        long userId = user("kakao-pd-6");
        long resumeId = resume(userId, "h6", AnalysisStatus.EMBEDDED);
        softDeletedAt(resumeId, NOW.minus(DELAY));
        jdbcTemplate.update("UPDATE resumes SET original_file_bucket = 'missing-bucket-pd-6' WHERE id = ?", resumeId);

        serviceAt(NOW).runCycle();
        assertThat(rowExists(resumeId)).isTrue();
        assertThat(seeder.chunkCount(resumeId)).isEqualTo(1);

        jdbcTemplate.update("UPDATE resumes SET original_file_bucket = ? WHERE id = ?", BUCKET, resumeId);
        serviceAt(NOW).runCycle();
        assertThat(rowExists(resumeId)).isFalse();
        assertThat(s3Template.objectExists(BUCKET, keyOf(userId, "h6"))).isFalse();
    }

    @Test
    @DisplayName("타 유저·활성 이력서는 영향이 없고, 물리 삭제 후에는 같은 파일의 재업로드 조건(중복 없음·객체 없음)이 성립한다")
    void leavesOthersUntouchedAndAllowsReupload() {
        long userId = user("kakao-pd-7");
        long deleted = resume(userId, "h7", AnalysisStatus.EMBEDDED);
        softDeletedAt(deleted, NOW.minus(DELAY));
        long activeOther = resume(userId, "h7-other", AnalysisStatus.EMBEDDED);
        long other = user("kakao-pd-7-other");
        long othersDeleted = resume(other, "h7", AnalysisStatus.EMBEDDED);
        softDeletedAt(othersDeleted, NOW.minus(DELAY).plusSeconds(1)); // 미경과

        serviceAt(NOW).runCycle();

        assertThat(rowExists(deleted)).isFalse();
        assertThat(rowExists(activeOther)).isTrue();
        assertThat(s3Template.objectExists(BUCKET, keyOf(userId, "h7-other"))).isTrue();
        assertThat(rowExists(othersDeleted)).isTrue();
        assertThat(s3Template.objectExists(BUCKET, keyOf(other, "h7"))).isTrue();
        assertThat(resumeRepositoryService.existsActiveDuplicate(userId, "h7")).isFalse();
        assertThat(s3Template.objectExists(BUCKET, keyOf(userId, "h7"))).isFalse();
    }

    @Test
    @DisplayName("배치 실행 중(활성 참조 확인 직후) 같은 파일이 재업로드되어도 새 이력서의 객체가 남는다 — 잠금 내 존재 재확인·재저장")
    void reuploadDuringPhysicalDeleteKeepsNewResumeObject() throws Exception {
        long userId = user("kakao-pd-8");
        byte[] pdf = ResumePdfFixtures.pdfWithPages(1);
        String hash = sha256Hex(pdf);
        String key = keyOf(userId, hash);
        long old = resume(userId, hash, AnalysisStatus.EMBEDDED);
        softDeletedAt(old, NOW.minus(DELAY));
        ResumePhysicalDeleteService batch = serviceAt(NOW);
        AtomicBoolean interleaved = new AtomicBoolean();
        // 업로드의 잠금 밖 존재 확인이 "있음"을 본 직후 배치가 객체를 지우는 최악의 순서를 재현한다
        doAnswer(invocation -> {
            boolean exists = (boolean) invocation.callRealMethod();
            if (interleaved.compareAndSet(false, true)) {
                batch.runCycle();
            }
            return exists;
        }).when(s3Template).objectExists(eq(BUCKET), eq(key));

        resumeUploadService.upload(userId, new MockMultipartFile("file", "resume.pdf", "application/pdf", pdf), "재업로드");

        assertThat(interleaved).isTrue();
        assertThat(rowExists(old)).isFalse();
        assertThat(resumeRepositoryService.existsActiveDuplicate(userId, hash)).isTrue();
        assertThat(s3Template.objectExists(BUCKET, key)).isTrue();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("스케줄러는 resume.physical-delete-interval을 fixedDelay로 쓴다")
    void schedulerIsWiredToConfiguredInterval() throws Exception {
        Scheduled scheduled = ResumePhysicalDeleteScheduler.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${resume.physical-delete-interval}");
    }
}
