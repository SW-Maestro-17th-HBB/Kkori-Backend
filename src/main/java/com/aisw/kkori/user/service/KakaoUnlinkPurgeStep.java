package com.aisw.kkori.user.service;

import com.aisw.kkori.global.logging.LogMasker;
import com.aisw.kkori.global.oauth.KakaoUnlinkClient;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * 카카오 연결 해제 단계 (PRD deletion.md 기능 4) — 데이터 파기 뒤·종결 앞.
 *
 * <p>재료는 탈퇴 시 확보한 {@code deletion_log.provider_id} 스냅샷이다. 스냅샷이 NULL이면 이전 시도에서
 * 완료된 것({@code SKIPPED_ALREADY_UNLINKED}), 같은 회원번호의 <b>활성</b> 계정이 있으면 유예 초과 후 재가입한
 * 유저의 새 연결을 끊지 않기 위해 생략한다({@code SKIPPED_ACTIVE_ACCOUNT} — {@code PURGING} 중에는 로그인·가입이
 * 409로 차단되어 이 판정 뒤 새 계정이 생길 수 없다). 완료·생략 후 스냅샷을 펜싱 조건부로 NULL 처리한다.
 * 호출은 트랜잭션·잠금 밖이며, 실패는 예외로 던져 건이 {@code FAILED}가 된다(스냅샷 유지 → 재시도 시 재호출).
 * 로그에는 회원번호 원문 대신 HMAC 가명값만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkPurgeStep implements PurgeStep {

    private final UserRepositoryService userRepositoryService;
    private final KakaoUnlinkClient unlinkClient;
    private final LogMasker logMasker;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String key() {
        return PurgeDetail.STEP_UNLINK;
    }

    @Override
    public int order() {
        return ORDER_UNLINK;
    }

    @Override
    public PurgeDetail.StepResult execute(PurgeTarget target) {
        Optional<String> snapshot = userRepositoryService.findProviderSnapshot(target.deletionLogId());
        if (snapshot.isEmpty()) {
            log.info("unlink 생략 — 스냅샷 없음(이미 완료) (deletionLogId={})", target.deletionLogId());
            return PurgeDetail.StepResult.of(PurgeDetail.StepResult.SKIPPED_ALREADY_UNLINKED);
        }
        String providerId = snapshot.get();
        if (userRepositoryService.existsActiveByProviderId(providerId)) {
            clearSnapshot(target);
            log.info("unlink 생략 — 동일 회원번호의 활성 계정 존재(재가입) (deletionLogId={}, provider_id_hmac={})",
                    target.deletionLogId(), logMasker.mask(providerId));
            return PurgeDetail.StepResult.of(PurgeDetail.StepResult.SKIPPED_ACTIVE_ACCOUNT);
        }
        KakaoUnlinkClient.Outcome outcome = unlinkClient.unlink(providerId);
        clearSnapshot(target);
        log.info("카카오 unlink {} (deletionLogId={}, provider_id_hmac={})",
                outcome, target.deletionLogId(), logMasker.mask(providerId));
        return PurgeDetail.StepResult.of(PurgeDetail.StepResult.DONE);
    }

    /** 스냅샷 NULL 처리 — 펜싱 불일치(재선점)는 여기서 실패시키지 않는다(오케스트레이터의 다음 기록이 검출). */
    private void clearSnapshot(PurgeTarget target) {
        Boolean cleared = transactionTemplate.execute(status ->
                userRepositoryService.clearProviderSnapshot(target.deletionLogId(), target.claimedAt()));
        if (!Boolean.TRUE.equals(cleared)) {
            log.warn("스냅샷 NULL 처리 펜싱 불일치 — 재선점된 건 (deletionLogId={})", target.deletionLogId());
        }
    }
}
