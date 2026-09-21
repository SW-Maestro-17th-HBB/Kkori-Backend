package com.aisw.kkori.user.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccountPolicyProperties} 바인딩·fail-fast 검증 (PRD deletion.md 공통: 배치 실행·설정 —
 * 0 이하 기간은 기동 실패). {@link ApplicationContextRunner}로 Boot 바인더 경로를 실제로 태운다.
 */
class AccountPolicyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProps.class);

    @EnableConfigurationProperties(AccountPolicyProperties.class)
    static class EnableProps {
    }

    private static final String[] ALL = {
            "account.withdrawal-grace-period=3d",
            "account.purge-interval=10m",
            "account.purging-timeout=30m",
            "account.consent-retention=365d",
    };

    @org.junit.jupiter.api.Test
    @DisplayName("네 기간이 전부 주입되면 바인딩된다")
    void bindsAllDurations() {
        runner.withPropertyValues(ALL).run(ctx -> {
            assertThat(ctx).hasSingleBean(AccountPolicyProperties.class);
            AccountPolicyProperties props = ctx.getBean(AccountPolicyProperties.class);
            assertThat(props.withdrawalGracePeriod()).isEqualTo(Duration.ofDays(3));
            assertThat(props.purgeInterval()).isEqualTo(Duration.ofMinutes(10));
            assertThat(props.purgingTimeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(props.consentRetention()).isEqualTo(Duration.ofDays(365));
        });
    }

    @ParameterizedTest(name = "{0} 누락 시 기동 실패")
    @ValueSource(strings = {
            "account.withdrawal-grace-period",
            "account.purge-interval",
            "account.purging-timeout",
            "account.consent-retention",
    })
    @DisplayName("기간이 하나라도 누락되면 기동이 실패한다 (fail-fast)")
    void missingDurationFailsStartup(String missingKey) {
        String[] withoutOne = Arrays.stream(ALL)
                .filter(kv -> !kv.startsWith(missingKey + "="))
                .toArray(String[]::new);

        runner.withPropertyValues(withoutOne).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining(missingKey);
        });
    }

    @ParameterizedTest(name = "{0} 0 이하 시 기동 실패")
    @ValueSource(strings = {
            "account.withdrawal-grace-period",
            "account.purge-interval",
            "account.purging-timeout",
            "account.consent-retention",
    })
    @DisplayName("0 이하 기간은 기동이 실패한다 (fail-fast)")
    void nonPositiveDurationFailsStartup(String key) {
        String[] overridden = Arrays.stream(ALL)
                .map(kv -> kv.startsWith(key + "=") ? key + "=0s" : kv)
                .toArray(String[]::new);

        runner.withPropertyValues(overridden).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining(key);
        });
    }
}
