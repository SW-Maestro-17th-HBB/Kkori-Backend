package com.aisw.kkori.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시각 취득의 단일 원천(UTC). 도메인 시각은 전부 이 Clock에서 얻는다 —
 * {@code Instant.now()} 직접 호출 금지(PRD 공통: 시각 처리).
 * 테스트에서 고정 Clock으로 대체하면 유예 만료·Grace Period 같은
 * 시간 조건을 결정적으로 검증할 수 있다.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
