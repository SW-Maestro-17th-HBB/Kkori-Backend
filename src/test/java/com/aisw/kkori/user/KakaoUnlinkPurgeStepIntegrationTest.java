package com.aisw.kkori.user;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.global.oauth.KakaoUnlinkClient;
import com.aisw.kkori.global.oauth.KakaoUnlinkException;
import com.aisw.kkori.user.domain.DeletionLog;
import com.aisw.kkori.user.domain.DeletionStatus;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.service.DeletionPurgeService;
import com.aisw.kkori.user.service.UserService;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카카오 연결 해제 단계 (PRD {@code docs/requirements/user/deletion.md} 기능 4 검증 기준) — 실제 파기 배치 빈에
 * unlink 포트만 더블로 바꿔 검증한다(로컬·테스트는 카카오 호출 불가).
 */
@ExtendWith(OutputCaptureExtension.class)
class KakaoUnlinkPurgeStepIntegrationTest extends AuthIntegrationTestSupport {

    @Autowired
    private DeletionPurgeService purgeService;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private S3Template s3Template;

    @MockitoBean
    private KakaoUnlinkClient unlinkClient;

    /** 실제 이력서 단계가 prefix 목록 조회를 하므로 설정 버킷이 있어야 한다(운영에선 상시 존재). */
    @BeforeEach
    void ensureBucket() {
        if (!s3Template.bucketExists(TestcontainersConfiguration.TEST_BUCKET)) {
            s3Template.createBucket(TestcontainersConfiguration.TEST_BUCKET);
        }
    }

    private long expiredWithdrawnUser(String providerId) {
        User user = saveUser(providerId);
        userService.withdraw(user.getId());
        Instant past = Instant.now().minus(Duration.ofDays(4));
        jdbcTemplate.update("update users set deleted_at = ? where id = ?", Timestamp.from(past), user.getId());
        jdbcTemplate.update("update deletion_log set requested_at = ?, updated_at = ? where user_id = ?",
                Timestamp.from(past), Timestamp.from(past), user.getId());
        return user.getId();
    }

    private DeletionLog logOf(long userId) {
        return deletionLogRepository.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId).orElseThrow();
    }

    private String unlinkStatus(long userId) {
        return logOf(userId).getPurgeDetail().steps().get(PurgeDetail.STEP_UNLINK).status();
    }

    @Test
    @DisplayName("스냅샷 회원번호로 unlink를 1회 호출하고 성공 시 스냅샷 NULL·DONE·PURGED로 종결된다")
    void unlinksWithSnapshotAndClearsIt(CapturedOutput output) {
        when(unlinkClient.unlink("kakao-uk-1")).thenReturn(KakaoUnlinkClient.Outcome.UNLINKED);
        long userId = expiredWithdrawnUser("kakao-uk-1");

        purgeService.runCycle();

        verify(unlinkClient, times(1)).unlink("kakao-uk-1");
        DeletionLog log = logOf(userId);
        assertThat(log.getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(log.getProviderId()).isNull();
        assertThat(unlinkStatus(userId)).isEqualTo(PurgeDetail.StepResult.DONE);
        assertThat(output.getOut()).contains("카카오 unlink UNLINKED").doesNotContain("kakao-uk-1");
    }

    @Test
    @DisplayName("-101(이미 연결 해제) 응답도 완료로 처리되어 스냅샷이 NULL이 된다")
    void alreadyUnlinkedIsCompletion() {
        when(unlinkClient.unlink("kakao-uk-2")).thenReturn(KakaoUnlinkClient.Outcome.ALREADY_UNLINKED);
        long userId = expiredWithdrawnUser("kakao-uk-2");

        purgeService.runCycle();

        assertThat(logOf(userId).getProviderId()).isNull();
        assertThat(unlinkStatus(userId)).isEqualTo(PurgeDetail.StepResult.DONE);
        assertThat(logOf(userId).getStatus()).isEqualTo(DeletionStatus.PURGED);
    }

    @Test
    @DisplayName("스냅샷과 같은 회원번호의 활성 계정(유예 초과 재가입)이 있으면 unlink 없이 SKIPPED_ACTIVE_ACCOUNT·스냅샷 NULL이며 새 계정은 무영향")
    void skipsWhenActiveAccountWithSameProviderExists() {
        long oldUserId = expiredWithdrawnUser("kakao-uk-3");
        // 유예 초과 재가입 재현 — 옛 계정은 식별정보가 이미 마스킹됐고, 같은 회원번호의 새 활성 계정이 있다
        jdbcTemplate.update("update users set provider_id = ?, email = null, name = null where id = ?",
                "PURGED_" + oldUserId, oldUserId);
        User newUser = saveUser("kakao-uk-3");

        purgeService.runCycle();

        verify(unlinkClient, never()).unlink(anyString());
        assertThat(logOf(oldUserId).getProviderId()).isNull();
        assertThat(unlinkStatus(oldUserId)).isEqualTo(PurgeDetail.StepResult.SKIPPED_ACTIVE_ACCOUNT);
        assertThat(logOf(oldUserId).getStatus()).isEqualTo(DeletionStatus.PURGED);
        User untouched = userRepository.findById(newUser.getId()).orElseThrow();
        assertThat(untouched.getProviderId()).isEqualTo("kakao-uk-3");
        assertThat(untouched.getEmail()).isEqualTo("kakao-uk-3@example.com");
        assertThat(untouched.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("스냅샷이 이미 NULL인 건(재시도)은 호출 없이 SKIPPED_ALREADY_UNLINKED로 통과한다")
    void skipsWhenSnapshotAlreadyCleared() {
        long userId = expiredWithdrawnUser("kakao-uk-4");
        jdbcTemplate.update("update deletion_log set provider_id = null where user_id = ?", userId);

        purgeService.runCycle();

        verify(unlinkClient, never()).unlink(anyString());
        assertThat(unlinkStatus(userId)).isEqualTo(PurgeDetail.StepResult.SKIPPED_ALREADY_UNLINKED);
        assertThat(logOf(userId).getStatus()).isEqualTo(DeletionStatus.PURGED);
    }

    @Test
    @DisplayName("unlink 실패 시 FAILED·스냅샷 유지·식별정보 미마스킹이고, 다음 회차에 다시 호출해 완료한다")
    void failureKeepsSnapshotAndRetriesNextCycle() {
        when(unlinkClient.unlink("kakao-uk-5"))
                .thenThrow(new KakaoUnlinkException("카카오 unlink 통신 실패 (ResourceAccessException)", null))
                .thenReturn(KakaoUnlinkClient.Outcome.UNLINKED);
        long userId = expiredWithdrawnUser("kakao-uk-5");

        purgeService.runCycle();

        DeletionLog failed = logOf(userId);
        assertThat(failed.getStatus()).isEqualTo(DeletionStatus.FAILED);
        assertThat(failed.getProviderId()).isEqualTo("kakao-uk-5");
        assertThat(unlinkStatus(userId)).isEqualTo(PurgeDetail.StepResult.FAILED);
        assertThat(failed.getPurgeDetail().lastError()).startsWith("KakaoUnlinkException");
        assertThat(userRepository.findById(userId).orElseThrow().getProviderId()).isEqualTo("kakao-uk-5");

        purgeService.runCycle();

        verify(unlinkClient, times(2)).unlink("kakao-uk-5");
        DeletionLog purged = logOf(userId);
        assertThat(purged.getStatus()).isEqualTo(DeletionStatus.PURGED);
        assertThat(purged.getProviderId()).isNull();
        assertThat(purged.getPurgeDetail().attempts()).isEqualTo(2);
        assertThat(userRepository.findById(userId).orElseThrow().getProviderId()).isEqualTo("PURGED_" + userId);
    }
}
