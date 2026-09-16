package com.aisw.kkori;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 테스트 컨텍스트의 배치 빈(파기·이력서 물리 삭제·RT 청소) 비활성화 — {@code app.batch.enabled=false}를
 * <b>빈 조건 평가 전에</b> Environment에 넣는다. 통합 테스트는 유예 초과 시나리오를 위해 과거 시각의 탈퇴 건을
 * 시딩하는데, 백그라운드 회차가 그 건을 실제로 파기하면 검증이 깨진다. 배치 로직은 각 테스트가 스케줄 메서드를
 * 직접 호출해 검증한다(스위퍼 테스트 관례).
 *
 * <p>{@code DynamicPropertyRegistrar}로는 부족하다 — 빈 정의 등록이 끝난 뒤 실행되어 {@code @ConditionalOnProperty}
 * 평가(컴포넌트 스캔 시점)에 반영되지 않는다. 테스트 클래스패스의 {@code META-INF/spring.factories}로 등록되어
 * 테스트 전용이며, 각 배치 테스트가 스케줄러 빈 부재를 단언한다.
 */
public class BatchDisablingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "testBatchDisabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of("app.batch.enabled", "false")));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
