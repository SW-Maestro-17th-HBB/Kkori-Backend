package com.aisw.kkori.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RT 청소 배치 트리거 (PRD deletion.md 기능 6·공통: 배치 실행·설정) — 앱 내 {@code @Scheduled(fixedDelay)}.
 * {@code app.batch.enabled=false}면 등록되지 않는다(통합 테스트 컨텍스트 전용 스위치).
 */
@Component
@ConditionalOnProperty(name = "app.batch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RefreshTokenCleanupScheduler {

    private final RefreshTokenCleanupService cleanupService;

    @Scheduled(fixedDelayString = "${jwt.refresh-token-cleanup-interval}")
    public void run() {
        cleanupService.runCycle();
    }
}
