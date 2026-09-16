package com.aisw.kkori.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 계정 정책 설정 ({@code account.*}).
 *
 * <ul>
 * <li>{@code withdrawalGracePeriod} — 탈퇴 후 개인정보 파기까지의 유예 기간(기본 3일). 탈퇴 응답의
 *     {@code purgeScheduledAt} 계산, 재로그인 시 복구 가능 여부 판정(HBB1-245), 파기 배치의
 *     유예 경과 판정(HBB1-13)에 쓰인다.</li>
 * <li>{@code purgeInterval} — 파기 배치 회차 간격(fixedDelay, PRD deletion.md 기능 2).</li>
 * <li>{@code purgingTimeout} — {@code PURGING} 선점 후 이 시간이 지나도록 종결되지 않으면 stale로 보고
 *     다른 인스턴스가 재선점한다(선점 인스턴스 중단 회수).</li>
 * <li>{@code consentRetention} — 파기 완료({@code purged_at}) 후 동의 이력·가명 users 행의 보존 기간
 *     (PRD deletion.md 기능 7). {@code Duration}은 연 단위를 지원하지 않아 일수로 표기한다(365d).</li>
 * </ul>
 *
 * <p>잘못된 설정(0 이하 기간)은 부팅 시점에 실패시킨다(fail-fast).
 */
@ConfigurationProperties(prefix = "account")
public record AccountPolicyProperties(
        Duration withdrawalGracePeriod,
        Duration purgeInterval,
        Duration purgingTimeout,
        Duration consentRetention
) {

    public AccountPolicyProperties {
        requirePositive(withdrawalGracePeriod, "account.withdrawal-grace-period");
        requirePositive(purgeInterval, "account.purge-interval");
        requirePositive(purgingTimeout, "account.purging-timeout");
        requirePositive(consentRetention, "account.consent-retention");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("%s은(는) 0보다 큰 기간이어야 합니다".formatted(name));
        }
    }
}
