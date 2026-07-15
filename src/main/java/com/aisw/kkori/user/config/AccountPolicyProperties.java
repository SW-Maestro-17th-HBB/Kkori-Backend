package com.aisw.kkori.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 계정 정책 설정 ({@code account.*}).
 *
 * <p>{@code withdrawalGracePeriod}는 탈퇴 후 개인정보 파기까지의 유예 기간이다(기본 3일).
 * 탈퇴 응답의 {@code purgeScheduledAt} 계산과 재로그인 시 복구 가능 여부 판정(HBB1-245)에 쓰인다.
 *
 * <p>잘못된 설정(0 이하 기간)은 부팅 시점에 실패시킨다(fail-fast).
 */
@ConfigurationProperties(prefix = "account")
public record AccountPolicyProperties(
        Duration withdrawalGracePeriod
) {

    public AccountPolicyProperties {
        if (withdrawalGracePeriod == null || withdrawalGracePeriod.isZero() || withdrawalGracePeriod.isNegative()) {
            throw new IllegalArgumentException("account.withdrawal-grace-period은(는) 0보다 큰 기간이어야 합니다");
        }
    }
}
