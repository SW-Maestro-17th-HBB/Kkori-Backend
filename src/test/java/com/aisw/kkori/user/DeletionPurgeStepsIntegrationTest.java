package com.aisw.kkori.user;

import com.aisw.kkori.ResumeSeeder;
import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.auth.domain.RefreshToken;
import com.aisw.kkori.global.oauth.KakaoUnlinkClient;
import com.aisw.kkori.report.ReportFixtures;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.session.service.SessionRoomManager;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.service.DeletionPurgeService;
import com.aisw.kkori.user.service.PurgeStep;
import com.aisw.kkori.user.service.PurgeTarget;
import com.aisw.kkori.user.service.UserService;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실데이터 파기 단계 (PRD {@code docs/requirements/user/deletion.md} 기능 3 검증 기준) — 실제 배치 빈과
 * MinIO·PostgreSQL 실물로 검증한다. Worker·에이전트 소유 테이블은 계약 픽스처 DDL로 만든다.
 */
@Import(ReportFixtures.class)
class DeletionPurgeStepsIntegrationTest extends AuthIntegrationTestSupport {

    private static final String BUCKET = TestcontainersConfiguration.TEST_BUCKET;
    private static final byte[] BYTES = "bytes".getBytes();

    @Autowired private DeletionPurgeService purgeService;
    @Autowired private UserService userService;
    @Autowired private List<PurgeStep> steps;
    @Autowired private ResumeRepository resumeRepository;
    @Autowired private ResumeAnalysisStatusRepository statusRepository;
    @Autowired private InterviewSessionRepository sessionRepository;
    @Autowired private ReportFixtures reportFixtures;
    @Autowired private S3Template s3Template;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SessionRoomManager roomManager;

    private ResumeSeeder seeder;

    /** 카카오 unlink는 로컬·테스트에서 호출 불가 — 포트를 더블로 대체(계약은 KakaoUnlinkPurgeStepIntegrationTest). */
    @MockitoBean
    private KakaoUnlinkClient unlinkClient;

    @BeforeEach
    void prepareFixtures() {
        when(unlinkClient.unlink(anyString())).thenReturn(KakaoUnlinkClient.Outcome.UNLINKED);
        if (!s3Template.bucketExists(BUCKET)) {
            s3Template.createBucket(BUCKET);
        }
        seeder = new ResumeSeeder(resumeRepository, statusRepository, jdbcTemplate);
        seeder.ensureChunkTable(); // Worker 소유 resume_chunks — 계약 픽스처 DDL
        jdbcTemplate.update("DELETE FROM resume_chunks");
        reportFixtures.deleteAll(); // interview_transcript · report_generation_jobs 픽스처 DDL 보장 + 정리
        sessionRepository.deleteAll();
        statusRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM resumes");
    }

    // ── 시딩 헬퍼 ──

    /** 탈퇴 후 유예 경과 상태의 유저 — 실제 탈퇴 경로를 거친 뒤 시각만 과거로. */
    private long expiredWithdrawnUser(String providerId) {
        User user = saveUser(providerId);
        userService.withdraw(user.getId());
        Instant past = Instant.now().minus(Duration.ofDays(4));
        jdbcTemplate.update("update users set deleted_at = ? where id = ?", Timestamp.from(past), user.getId());
        jdbcTemplate.update("update deletion_log set requested_at = ?, updated_at = ? where user_id = ?",
                Timestamp.from(past), Timestamp.from(past), user.getId());
        return user.getId();
    }

    private long resumeWithObject(long userId, String hash, boolean softDeleted, int chunks) {
        String key = "resumes/" + userId + "/" + hash + ".pdf";
        putObject(BUCKET, key);
        Resume resume = resumeRepository.save(Resume.builder()
                .userId(userId).title("이력서").fileHash(hash)
                .originalFileBucket(BUCKET).originalFileKey(key).originalFileName("resume.pdf")
                .fileSize(1L).mimeType("application/pdf").pageCount(1).build());
        statusRepository.save(ResumeAnalysisStatus.init(resume));
        for (int i = 0; i < chunks; i++) {
            seeder.chunk(resume.getId());
        }
        if (softDeleted) {
            jdbcTemplate.update("UPDATE resumes SET deleted_at = now() WHERE id = ?", resume.getId());
        }
        return resume.getId();
    }

    private long session(long userId, SessionStatus status, String room, String recordingKey, boolean transcript) {
        InterviewSession session = sessionRepository.save(InterviewSession.pending(
                userId, null, InterviewType.THIRTY_MIN, Position.BACKEND, room));
        jdbcTemplate.update("UPDATE interview_session SET status = ? WHERE id = ?", status.name(), session.getId());
        if (recordingKey != null) {
            putObject(BUCKET, recordingKey);
            jdbcTemplate.update("UPDATE interview_session SET recording_bucket = ?, recording_object_key = ? WHERE id = ?",
                    BUCKET, recordingKey, session.getId());
        }
        if (transcript) {
            reportFixtures.transcript(session.getId(), "[{\"speaker\":\"USER\",\"content\":\"개인정보 발화\"}]");
        }
        return session.getId();
    }

    private void putObject(String bucket, String key) {
        s3Template.upload(bucket, key, new ByteArrayInputStream(BYTES), ObjectMetadata.builder().build());
    }

    private void refreshToken(long userId, String hash) {
        refreshTokenRepository.save(RefreshToken.issue(userId, hash, "jti-" + hash,
                Instant.now(), Instant.now().plus(Duration.ofDays(14))));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private DeletionLog logOf(long userId) {
        return deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId).orElseThrow();
    }

    private PurgeTarget targetOf(long userId) {
        DeletionLog log = logOf(userId);
        return new PurgeTarget(log.getId(), userId, log.getUpdatedAt());
    }

    private PurgeStep step(String key) {
        return steps.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    // ── 검증 ──

    @Test
    @DisplayName("이력서 — 활성·soft delete 이력서의 S3 원본·고아 객체·청크·분석 상태·행이 전부 제거되고 타 유저는 영향이 없다")
    void purgesResumesWithObjectsChunksAndOrphans() {
        long userId = expiredWithdrawnUser("kakao-ps-1");
        long active = resumeWithObject(userId, "a1", false, 2);
        long deleted = resumeWithObject(userId, "a2", true, 3);
        String orphanKey = "resumes/" + userId + "/orphan.pdf";
        putObject(BUCKET, orphanKey);
        long other = saveUser("kakao-ps-1-other").getId();
        long othersResume = resumeWithObject(other, "b1", false, 1);

        purgeService.runCycle();

        assertThat(s3Template.objectExists(BUCKET, "resumes/" + userId + "/a1.pdf")).isFalse();
        assertThat(s3Template.objectExists(BUCKET, "resumes/" + userId + "/a2.pdf")).isFalse();
        assertThat(s3Template.objectExists(BUCKET, orphanKey)).isFalse();
        assertThat(s3Template.objectExists(BUCKET, "resumes/" + other + "/b1.pdf")).isTrue();
        assertThat(count("SELECT count(*) FROM resumes WHERE id IN (?, ?)", active, deleted)).isZero();
        assertThat(count("SELECT count(*) FROM resume_analysis_status WHERE resume_id IN (?, ?)", active, deleted)).isZero();
        assertThat(count("SELECT count(*) FROM resume_chunks WHERE resume_id IN (?, ?)", active, deleted)).isZero();
        assertThat(count("SELECT count(*) FROM resumes WHERE id = ?", othersResume)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM resume_chunks WHERE resume_id = ?", othersResume)).isEqualTo(1);

        PurgeDetail.StepResult result = logOf(userId).getPurgeDetail().steps().get(PurgeDetail.STEP_RESUMES);
        assertThat(result.status()).isEqualTo(PurgeDetail.StepResult.DONE);
        assertThat(result.rows()).isEqualTo(2);
        assertThat(result.chunks()).isEqualTo(5);
        assertThat(result.s3Objects()).isEqualTo(3);
    }

    @Test
    @DisplayName("세션 — 녹음 객체 삭제 후 컬럼 NULL, 대본은 빈 배열 마스킹 + deleted_at, 세션은 soft delete, 잔존 non-terminal은 ABORTED + 룸 삭제")
    void purgesSessionsRecordingsAndTranscripts() {
        long userId = expiredWithdrawnUser("kakao-ps-2");
        long recorded = session(userId, SessionStatus.ENDED, "room-ps-2a", "recordings/ps-2a.ogg", true);
        long plain = session(userId, SessionStatus.ENDED, "room-ps-2b", null, true);
        long leftover = session(userId, SessionStatus.ACTIVE, "room-ps-2c", null, false);
        long other = saveUser("kakao-ps-2-other").getId();
        long othersSession = session(other, SessionStatus.ENDED, "room-ps-2d", "recordings/ps-2d.ogg", true);

        purgeService.runCycle();

        assertThat(s3Template.objectExists(BUCKET, "recordings/ps-2a.ogg")).isFalse();
        assertThat(s3Template.objectExists(BUCKET, "recordings/ps-2d.ogg")).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT recording_object_key FROM interview_session WHERE id = ?",
                String.class, recorded)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT content::text FROM interview_transcript WHERE session_id = ?",
                String.class, recorded)).isEqualTo("[]");
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM interview_transcript WHERE session_id = ?",
                Timestamp.class, plain)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT content::text FROM interview_transcript WHERE session_id = ?",
                String.class, othersSession)).contains("개인정보 발화");
        assertThat(count("SELECT count(*) FROM interview_session WHERE user_id = ? AND deleted_at IS NOT NULL", userId))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM interview_session WHERE id = ? AND deleted_at IS NULL", othersSession))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM interview_session WHERE id = ?",
                String.class, leftover)).isEqualTo("ABORTED");
        verify(roomManager).deleteRoomQuietly("room-ps-2c");

        PurgeDetail.StepResult result = logOf(userId).getPurgeDetail().steps().get(PurgeDetail.STEP_SESSIONS);
        assertThat(result.rows()).isEqualTo(3);
        assertThat(result.transcripts()).isEqualTo(2);
        assertThat(result.recordings()).isEqualTo(1);
        assertThat(result.abortedLeftovers()).isEqualTo(1);
    }

    @Test
    @DisplayName("리포트 — soft delete된 리포트를 포함해 4테이블에서 물리 삭제되고 타 유저는 영향이 없다")
    void purgesReportsAcrossFourTables() {
        long userId = expiredWithdrawnUser("kakao-ps-3");
        long evaluated = reportFixtures.evaluatedReport(userId, null);
        reportFixtures.job(evaluated, Instant.now(), 0);
        long softDeleted = reportFixtures.completedReport(userId, 70, Instant.now());
        jdbcTemplate.update("UPDATE reports SET deleted_at = now() WHERE id = ?", softDeleted);
        long other = saveUser("kakao-ps-3-other").getId();
        long othersReport = reportFixtures.evaluatedReport(other, null);
        reportFixtures.job(othersReport, Instant.now(), 0);

        purgeService.runCycle();

        assertThat(count("SELECT count(*) FROM reports WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM report_scores WHERE report_id IN (?, ?)", evaluated, softDeleted)).isZero();
        assertThat(count("SELECT count(*) FROM report_feedbacks WHERE report_id IN (?, ?)", evaluated, softDeleted)).isZero();
        assertThat(count("SELECT count(*) FROM report_generation_jobs WHERE report_id IN (?, ?)", evaluated, softDeleted)).isZero();
        assertThat(count("SELECT count(*) FROM reports WHERE id = ?", othersReport)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM report_feedbacks WHERE report_id = ?", othersReport)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM report_generation_jobs WHERE report_id = ?", othersReport)).isEqualTo(1);
        assertThat(logOf(userId).getPurgeDetail().steps().get(PurgeDetail.STEP_REPORTS).rows()).isEqualTo(2);
    }

    @Test
    @DisplayName("RT·식별정보·동의 — RT 행은 전부 삭제, users는 마스킹, user_consent는 건드리지 않고 PURGED로 종결된다")
    void purgesRefreshTokensAndMasksIdentifiersKeepingConsents() {
        long userId = expiredWithdrawnUser("kakao-ps-4");
        refreshToken(userId, "hash-ps-4a");
        refreshToken(userId, "hash-ps-4b");
        long other = saveUser("kakao-ps-4-other").getId();
        refreshToken(other, "hash-ps-4c");
        int consentsBefore = count("SELECT count(*) FROM user_consent WHERE user_id = ?", userId);

        purgeService.runCycle();

        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", other)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_consent WHERE user_id = ?", userId)).isEqualTo(consentsBefore);
        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getName()).isNull();
        assertThat(user.getProviderId()).isEqualTo("PURGED_" + userId);
        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(log.getProviderId()).isNull();
        assertThat(log.getPurgeDetail().steps().get(PurgeDetail.STEP_REFRESH_TOKENS).rows()).isEqualTo(2);
        // jsonb는 키 순서를 보존하지 않는다 — 구성만 단언
        assertThat(log.getPurgeDetail().steps().keySet()).containsExactlyInAnyOrder(
                PurgeDetail.STEP_RESUMES, PurgeDetail.STEP_SESSIONS, PurgeDetail.STEP_REPORTS,
                PurgeDetail.STEP_REFRESH_TOKENS, PurgeDetail.STEP_UNLINK, PurgeDetail.STEP_IDENTIFIERS);
    }

    @Test
    @DisplayName("각 단계는 파기 완료 상태에서 재실행해도 예외 없이 0건으로 끝난다 (멱등)")
    void stepsAreIdempotentAfterCompletion() {
        long userId = expiredWithdrawnUser("kakao-ps-5");
        resumeWithObject(userId, "c1", false, 1);
        session(userId, SessionStatus.ENDED, "room-ps-5", "recordings/ps-5.ogg", true);
        reportFixtures.job(reportFixtures.evaluatedReport(userId, null), Instant.now(), 0);
        refreshToken(userId, "hash-ps-5");
        purgeService.runCycle();
        PurgeTarget target = targetOf(userId);

        assertThat(step(PurgeDetail.STEP_RESUMES).execute(target).rows()).isZero();
        PurgeDetail.StepResult sessions = step(PurgeDetail.STEP_SESSIONS).execute(target);
        assertThat(sessions.transcripts()).isZero();
        assertThat(sessions.recordings()).isZero();
        assertThat(sessions.abortedLeftovers()).isZero();
        assertThat(step(PurgeDetail.STEP_REPORTS).execute(target).rows()).isZero();
        assertThat(step(PurgeDetail.STEP_REFRESH_TOKENS).execute(target).rows()).isZero();
        assertThat(logOf(userId).getStatus()).isEqualTo(DeletionStatus.PURGED);
    }

    @Test
    @DisplayName("S3 삭제 실패(존재하지 않는 버킷) 시 행이 남고 FAILED가 되며, 원인 해소 후 재시도가 PURGED로 완료된다")
    void s3FailureKeepsRowsAndRetrySucceeds() {
        long userId = expiredWithdrawnUser("kakao-ps-6");
        long resumeId = resumeWithObject(userId, "d1", false, 1);
        jdbcTemplate.update("UPDATE resumes SET original_file_bucket = 'missing-bucket-ps-6' WHERE id = ?", resumeId);

        purgeService.runCycle();

        DeletionLog failed = logOf(userId);
        assertThat(failed.getStatus()).isEqualTo(DeletionStatus.FAILED);
        assertThat(failed.getPurgeDetail().steps().get(PurgeDetail.STEP_RESUMES).status())
                .isEqualTo(PurgeDetail.StepResult.FAILED);
        assertThat(failed.getPurgeDetail().lastError()).isNotBlank();
        assertThat(count("SELECT count(*) FROM resumes WHERE id = ?", resumeId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM resume_chunks WHERE resume_id = ?", resumeId)).isEqualTo(1);
        assertThat(userRepository.findById(userId).orElseThrow().getProviderId()).isEqualTo("kakao-ps-6");

        jdbcTemplate.update("UPDATE resumes SET original_file_bucket = ? WHERE id = ?", BUCKET, resumeId);
        purgeService.runCycle();

        DeletionLog purged = logOf(userId);
        assertThat(purged.getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(purged.getPurgeDetail().attempts()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM resumes WHERE id = ?", resumeId)).isZero();
        assertThat(s3Template.objectExists(BUCKET, "resumes/" + userId + "/d1.pdf")).isFalse();
    }

    @Test
    @DisplayName("중간 단계(리포트) 실패 후 재시도하면 완료된 앞 단계는 실행하지 않고(기록 보존) 나머지를 완료해 PURGED가 된다")
    void retryAfterMidwayFailureCompletesRemainingSteps() {
        long userId = expiredWithdrawnUser("kakao-ps-7");
        resumeWithObject(userId, "e1", false, 1);
        reportFixtures.job(reportFixtures.evaluatedReport(userId, null), Instant.now(), 0);
        refreshToken(userId, "hash-ps-7");
        jdbcTemplate.execute("ALTER TABLE report_generation_jobs RENAME TO report_generation_jobs_backup");
        try {
            purgeService.runCycle();
        } finally {
            jdbcTemplate.execute("ALTER TABLE report_generation_jobs_backup RENAME TO report_generation_jobs");
        }
        DeletionLog failed = logOf(userId);
        assertThat(failed.getStatus()).isEqualTo(DeletionStatus.FAILED);
        assertThat(failed.getPurgeDetail().steps().get(PurgeDetail.STEP_RESUMES).rows()).isEqualTo(1);
        assertThat(failed.getPurgeDetail().steps().get(PurgeDetail.STEP_REPORTS).status())
                .isEqualTo(PurgeDetail.StepResult.FAILED);
        assertThat(count("SELECT count(*) FROM reports WHERE user_id = ?", userId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", userId)).isEqualTo(1);

        purgeService.runCycle();

        DeletionLog purged = logOf(userId);
        assertThat(purged.getStatus()).isEqualTo(DeletionStatus.PURGED);
        // 완료 단계는 재실행되지 않고 이전 실적(rows=1)이 보존된다
        assertThat(purged.getPurgeDetail().steps().get(PurgeDetail.STEP_RESUMES).rows()).isEqualTo(1);
        assertThat(purged.getPurgeDetail().steps().get(PurgeDetail.STEP_REPORTS).rows()).isEqualTo(1);
        assertThat(purged.getPurgeDetail().steps().get(PurgeDetail.STEP_REFRESH_TOKENS).rows()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reports WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("purge_detail에는 회원번호·이메일·이름·파일명·객체 키가 실리지 않는다")
    void purgeDetailContainsNoPersonalData() {
        long userId = expiredWithdrawnUser("kakao-ps-8");
        resumeWithObject(userId, "secret-hash", false, 1);
        session(userId, SessionStatus.ENDED, "room-ps-8", "recordings/secret-key.ogg", true);

        purgeService.runCycle();

        String json = jdbcTemplate.queryForObject("SELECT purge_detail::text FROM deletion_log WHERE user_id = ?",
                String.class, userId);
        assertThat(json)
                .doesNotContain("kakao-ps-8")
                .doesNotContain("@example.com")
                .doesNotContain("테스터")
                .doesNotContain("resume.pdf")
                .doesNotContain("secret-hash")
                .doesNotContain("secret-key")
                .doesNotContain("room-ps-8");
    }
}
