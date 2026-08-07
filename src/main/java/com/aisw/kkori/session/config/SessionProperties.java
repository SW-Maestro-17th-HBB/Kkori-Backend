package com.aisw.kkori.session.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 세션 수렴 스위퍼·재연결 설정 ({@code session.*} — PRD interview-session-completion.md ·
 * interview-session-reconnection.md 공통: 스위퍼·설정).
 *
 * <p>임계값은 실측 전 초기값이며 값 근거는 PRD에 있다: {@code endFallbackTimeout}(180s)은
 * 에이전트 종료 시퀀스 최악 소요(≈119s)에 안전 계수를 둔 값, {@code agentLostGrace}(90s)는
 * 재디스패치 복원 창(dispatch 왕복 + 워커 기동·join + webhook 지연 여유),
 * {@code staleRecoveryTimeout}(45m)은 최장 정상 ACTIVE 체류(≈40분)에 여유를 둔 webhook 최종
 * 유실 회수 임계, {@code reconnectWindow}(3m)는 <b>크로스 레포 계약값</b>이다 — AI 레포 설정과
 * 같은 값을 주입하며, 재입장 토큰 TTL과 INTERRUPTED 유예(창 + 코드 상수 마진)가 여기서
 * 파생된다(정합의 구조적 보장 — 재연결 PRD 값 정합 표).
 *
 * <p>잘못된 설정(0 이하 기간)은 부팅 시점에 실패시킨다(fail-fast — livekit.* 방침과 동일).
 */
@ConfigurationProperties(prefix = "session")
public record SessionProperties(
        Duration endFallbackTimeout,
        Duration agentLostGrace,
        Duration staleRecoveryTimeout,
        Duration sweepInterval,
        Duration reconnectWindow
) {

    public SessionProperties {
        requirePositive(endFallbackTimeout, "session.end-fallback-timeout");
        requirePositive(agentLostGrace, "session.agent-lost-grace");
        requirePositive(staleRecoveryTimeout, "session.stale-recovery-timeout");
        requirePositive(sweepInterval, "session.sweep-interval");
        requirePositive(reconnectWindow, "session.reconnect-window");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("%s은(는) 0보다 큰 기간이어야 합니다".formatted(name));
        }
    }
}
