package com.aisw.kkori.user.service;

import com.aisw.kkori.report.repositoryservice.ReportRepositoryService;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리포트 파기 단계 (PRD deletion.md 기능 3 — 3. 리포트): 유저의 모든 리포트(soft delete 포함)를
 * 피드백 → 점수 → Job(Worker 소유) → 리포트 순으로 물리 삭제한다. user 잠금 트랜잭션 하나.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportPurgeStep implements PurgeStep {

    private final ReportRepositoryService reportRepositoryService;
    private final UserRepositoryService userRepositoryService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String key() {
        return PurgeDetail.STEP_REPORTS;
    }

    @Override
    public int order() {
        return ORDER_REPORTS;
    }

    @Override
    public PurgeDetail.StepResult execute(PurgeTarget target) {
        int rows = transactionTemplate.execute(status -> {
            userRepositoryService.lockUser(target.userId());
            return reportRepositoryService.purgeByUserId(target.userId());
        });
        log.info("리포트 파기 (userId={}, rows={})", target.userId(), rows);
        return new PurgeDetail.StepResult(PurgeDetail.StepResult.DONE, rows, null, null, null, null, null);
    }
}
