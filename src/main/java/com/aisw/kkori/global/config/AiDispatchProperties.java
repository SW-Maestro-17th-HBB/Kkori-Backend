package com.aisw.kkori.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * AI 워커 디스패치 방식 설정 ({@code app.ai-dispatch.*}) — 동기/비동기 부하 테스트 비교용 (HBB1-327).
 *
 * <p>{@code mode}가 SYNC일 때만 {@code workerBaseUrl}·{@code syncReadTimeout}이 필수다 —
 * 비동기로만 운영하는 환경이 쓰지 않는 값까지 주입할 필요가 없도록. SYNC인데 값이 빠지면
 * 부팅 시점에 실패시킨다(fail-fast). 상세 배경과 전환 방법은 docs/experiments/sync-dispatch.md 참조.
 */
@ConfigurationProperties(prefix = "app.ai-dispatch")
public record AiDispatchProperties(
        DispatchMode mode,
        String workerBaseUrl,
        Duration syncReadTimeout
) {

    public enum DispatchMode { ASYNC, SYNC }

    public AiDispatchProperties {
        if (mode == null) {
            mode = DispatchMode.ASYNC;   // 미설정 = 비동기 (빈 선택의 matchIfMissing과 일치)
        }
        if (mode == DispatchMode.SYNC) {
            if (!StringUtils.hasText(workerBaseUrl)) {
                throw new IllegalArgumentException(
                        "app.ai-dispatch.mode=sync에는 app.ai-dispatch.worker-base-url이 필요합니다");
            }
            if (syncReadTimeout == null || syncReadTimeout.isZero() || syncReadTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "app.ai-dispatch.mode=sync에는 0보다 큰 app.ai-dispatch.sync-read-timeout이 필요합니다");
            }
        }
    }
}
