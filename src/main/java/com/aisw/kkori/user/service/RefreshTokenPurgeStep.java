package com.aisw.kkori.user.service;

import com.aisw.kkori.auth.repositoryservice.AuthRepositoryService;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh Token 파기 단계 (PRD deletion.md 기능 3 — 4. RT): 유저의 RT 행 전부 삭제. 탈퇴 시 전량 폐기된 뒤라
 * 재사용 감지 재료 가치가 없고, {@code user_id}로 연결되는 잔여 행을 남기지 않는다. 잠금 순서 user → RT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenPurgeStep implements PurgeStep {

    private final AuthRepositoryService authRepositoryService;
    private final UserRepositoryService userRepositoryService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String key() {
        return PurgeDetail.STEP_REFRESH_TOKENS;
    }

    @Override
    public int order() {
        return ORDER_REFRESH_TOKENS;
    }

    @Override
    public PurgeDetail.StepResult execute(PurgeTarget target) {
        int rows = transactionTemplate.execute(status -> {
            userRepositoryService.lockUser(target.userId());
            return authRepositoryService.deleteAllByUserId(target.userId());
        });
        log.info("RT 파기 (userId={}, rows={})", target.userId(), rows);
        return new PurgeDetail.StepResult(PurgeDetail.StepResult.DONE, rows, null, null, null, null, null);
    }
}
