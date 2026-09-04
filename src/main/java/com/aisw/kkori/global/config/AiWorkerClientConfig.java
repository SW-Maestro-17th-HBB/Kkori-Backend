package com.aisw.kkori.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 동기 디스패치용 AI 워커 HTTP 클라이언트 (HBB1-327). {@code app.ai-dispatch.mode=sync}일 때만 생성.
 *
 * <p>read timeout을 전역 기본({@code spring.http.client.read-timeout} 5s) 대신 별도 설정으로
 * 받는 이유: 동기 호출은 워커의 분석 완료까지 기다리는 것이 목적이라 수십 초가 정상 소요 시간이다.
 * 전역값을 쓰면 모든 정상 호출이 타임아웃으로 실패한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.ai-dispatch.mode", havingValue = "sync")
public class AiWorkerClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public RestClient aiWorkerRestClient(RestClient.Builder builder, AiDispatchProperties properties) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(properties.syncReadTimeout());
        return builder
                .baseUrl(properties.workerBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
