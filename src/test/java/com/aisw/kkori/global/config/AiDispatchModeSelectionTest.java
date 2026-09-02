package com.aisw.kkori.global.config;

import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.resume.service.ResumeAnalysisRequestPublisher;
import com.aisw.kkori.resume.service.ResumeAnalysisRequester;
import com.aisw.kkori.resume.service.ResumeAnalysisSyncHttpRequester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

/**
 * {@code app.ai-dispatch.mode}에 따른 {@link ResumeAnalysisRequester} 구현체 선택 검증 (HBB1-327).
 *
 * <p>실제 부팅에서는 잘못된 mode 값이 {@link AiDispatchProperties} enum 바인딩 실패로도 잡히지만,
 * 여기서는 조건부 빈 등록만 떼어 검증한다 — 잘못된 값이면 어느 구현체도 등록되지 않아
 * 주입 지점에서 기동이 실패한다(조용한 폴백 없음).
 */
class AiDispatchModeSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(RestClient.class, () -> mock(RestClient.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(ResumeRepositoryService.class, () -> mock(ResumeRepositoryService.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(ResumeAnalysisRequestPublisher.class, ResumeAnalysisSyncHttpRequester.class);

    @ParameterizedTest(name = "mode={0} → {1}")
    @MethodSource("modeToImplementation")
    @DisplayName("mode 값에 따라 구현체가 하나만 선택된다 (미설정 = async)")
    void selectsSingleImplementationByMode(String[] properties, Class<?> expectedImplementation) {
        runner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasSingleBean(ResumeAnalysisRequester.class);
            assertThat(context.getBean(ResumeAnalysisRequester.class)).isInstanceOf(expectedImplementation);
        });
    }

    static Stream<Arguments> modeToImplementation() {
        return Stream.of(
                arguments(new String[]{}, ResumeAnalysisRequestPublisher.class),
                arguments(new String[]{"app.ai-dispatch.mode=async"}, ResumeAnalysisRequestPublisher.class),
                arguments(new String[]{"app.ai-dispatch.mode=sync"}, ResumeAnalysisSyncHttpRequester.class));
    }

    @Test
    @DisplayName("잘못된 mode 값이면 어느 구현체도 등록되지 않는다")
    void invalidMode_registersNoImplementation() {
        runner.withPropertyValues("app.ai-dispatch.mode=banana")
                .run(context -> assertThat(context).doesNotHaveBean(ResumeAnalysisRequester.class));
    }
}
