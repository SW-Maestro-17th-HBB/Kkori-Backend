package com.aisw.kkori.global.config;

import com.aisw.kkori.global.sse.StatusChannelListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.List;

/**
 * 상태 이벤트 Pub/Sub 구독 컨테이너 — {@link StatusChannelListener} 빈을 모두 찾아 각자의 채널에 등록한다.
 *
 * <p>컨테이너는 하나만 두고 채널만 여러 개 등록한다(구독 연결 하나 공유). Redis 순단 시 컨테이너가
 * 다시 접속해 다시 구독하며(기본 5초 간격), 그 사이 발행된 이벤트는 저장되지 않아 사라진다.
 * SSE는 유실을 허용하고 복구는 REST가 담당한다는 PRD 규칙에 따라 그대로 둔다.
 */
@Slf4j
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer statusChannelListenerContainer(
            RedisConnectionFactory connectionFactory,
            List<StatusChannelListener> listeners
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 리스너 예외는 컨테이너가 잡아 여기로 넘긴다 — 구독은 유지되고 로그만 남긴다
        container.setErrorHandler(t -> log.warn("상태 이벤트 채널 처리 오류 — 구독 유지", t));
        for (StatusChannelListener listener : listeners) {
            container.addMessageListener(listener, new ChannelTopic(listener.channel()));
            log.info("상태 이벤트 채널 구독 등록: {}", listener.channel());
        }
        return container;
    }
}
