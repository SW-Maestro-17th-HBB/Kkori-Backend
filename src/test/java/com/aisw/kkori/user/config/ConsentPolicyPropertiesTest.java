package com.aisw.kkori.user.config;

import com.aisw.kkori.user.domain.ConsentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConsentPolicyProperties} 바인딩·fail-fast 검증.
 *
 * <p>PRD {@code docs/requirements/user/consent.md} 기능 1 검증 기준: 버전 설정 미주입 또는
 * 1 미만 값으로 기동 시 기동이 실패해야 한다. "기동 실패"를 증명해야 하므로 생성자 단위 테스트가
 * 아닌 {@link ApplicationContextRunner}로 Boot 바인더 경로를 실제로 태운다(Testcontainers 불필요).
 */
class ConsentPolicyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProps.class);

    @EnableConfigurationProperties(ConsentPolicyProperties.class)
    static class EnableProps {
    }

    private static final String[] ALL_VERSIONS = {
            "consent.versions.privacy=1",
            "consent.versions.audio_usage=1",
            "consent.versions.resume_usage=1",
            "consent.versions.marketing=2",
    };

    @Test
    @DisplayName("전 항목이 주입되면 바인딩되고 versionOf가 항목별 버전을 반환한다 (스네이크 키 → enum 관대 바인딩)")
    void bindsAllTypes() {
        runner.withPropertyValues(ALL_VERSIONS).run(ctx -> {
            assertThat(ctx).hasSingleBean(ConsentPolicyProperties.class);
            ConsentPolicyProperties props = ctx.getBean(ConsentPolicyProperties.class);
            assertThat(props.versionOf(ConsentType.PRIVACY)).isEqualTo(1);
            assertThat(props.versionOf(ConsentType.AUDIO_USAGE)).isEqualTo(1);
            assertThat(props.versionOf(ConsentType.RESUME_USAGE)).isEqualTo(1);
            assertThat(props.versionOf(ConsentType.MARKETING)).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("versions 맵은 불변이다 — put 시도 시 UnsupportedOperationException")
    void versionsMapIsImmutable() {
        runner.withPropertyValues(ALL_VERSIONS).run(ctx -> {
            ConsentPolicyProperties props = ctx.getBean(ConsentPolicyProperties.class);
            assertThatThrownBy(() -> props.versions().put(ConsentType.PRIVACY, 99))
                    .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    @DisplayName("항목이 하나라도 누락되면 기동이 실패한다 (fail-fast)")
    void missingTypeFailsStartup() {
        runner.withPropertyValues(
                "consent.versions.privacy=1",
                "consent.versions.audio_usage=1",
                "consent.versions.resume_usage=1"
                // marketing 누락
        ).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("1 미만 버전은 기동이 실패한다 (fail-fast)")
    void nonPositiveVersionFailsStartup() {
        runner.withPropertyValues(
                "consent.versions.privacy=0",
                "consent.versions.audio_usage=1",
                "consent.versions.resume_usage=1",
                "consent.versions.marketing=1"
        ).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("consent.versions 자체가 없으면 기동이 실패한다 (fail-fast)")
    void absentVersionsFailsStartup() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }
}
