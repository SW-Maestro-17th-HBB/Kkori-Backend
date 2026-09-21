package com.aisw.kkori.session.scheduler;

import com.aisw.kkori.session.service.SessionSweeper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 세션 수렴 스위퍼 트리거 — 앱 내 {@code @Scheduled(fixedDelay)}. 로직은 {@link SessionSweeper}에 있고 여기는 호출만
 * 한다. {@code app.batch.enabled=false}면 등록되지 않는다(테스트는 스위퍼를 고정 Clock으로 직접 구성해 {@code sweep()}을 부른다).
 */
@Component
@ConditionalOnProperty(name = "app.batch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SessionSweepScheduler {

    private final SessionSweeper sweeper;

    @Scheduled(fixedDelayString = "${session.sweep-interval}")
    public void run() {
        sweeper.sweep();
    }
}
