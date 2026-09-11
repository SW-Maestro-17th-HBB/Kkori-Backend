package com.aisw.kkori.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 파기 배치 트리거 (PRD deletion.md 공통: 배치 실행·설정) — 앱 내 {@code @Scheduled(fixedDelay)}.
 * 회차가 겹치지 않으며, 다중 인스턴스 동시 실행은 {@link DeletionPurgeService}의 조건부 선점이 무해화한다.
 *
 * <p>{@code app.batch.enabled=false}면 등록되지 않는다 — 통합 테스트 컨텍스트 전용 스위치.
 */
@Component
@ConditionalOnProperty(name = "app.batch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DeletionPurgeScheduler {

    private final DeletionPurgeService purgeService;

    @Scheduled(fixedDelayString = "${account.purge-interval}")
    public void run() {
        purgeService.runCycle();
    }
}
