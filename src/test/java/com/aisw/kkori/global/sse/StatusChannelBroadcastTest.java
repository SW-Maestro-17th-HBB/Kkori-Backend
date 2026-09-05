package com.aisw.kkori.global.sse;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.resume.dto.ResumeStatusChangedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 다중 인스턴스 재발 방지 테스트 (HBB1-332) — 상태 이벤트 채널을 구독하는 구독자가 여럿이어도
 * 각 구독자가 모든 이벤트를 받는지 확인한다.
 *
 * <p>구독 컨테이너 2개를 따로 띄워 인스턴스 2대 역할을 하게 한다. Consumer Group 하나로 스트림을 읽던 이전
 * 구조에서는 같은 조건에서 두 소비자가 이벤트를 나눠 가져가 각각 절반씩만 받았다(로컬에서 측정 50/100).
 * 앱 자체의 구독자도 같은 채널을 듣고 있지만, 이벤트의 userId에 해당하는 SSE 연결이 없어 조용히 버린다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StatusChannelBroadcastTest {

    private static final int EVENT_COUNT = 100;

    @Autowired RedisConnectionFactory connectionFactory;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("같은 채널을 구독하는 인스턴스 2대가 각각 모든 상태 이벤트를 받는다")
    void everySubscriberReceivesEveryEvent() throws Exception {
        String channel = ResumeStatusChangedMessage.CHANNEL;
        List<String> receivedByA = new CopyOnWriteArrayList<>();
        List<String> receivedByB = new CopyOnWriteArrayList<>();
        RedisMessageListenerContainer instanceA = subscribe(channel, receivedByA);
        RedisMessageListenerContainer instanceB = subscribe(channel, receivedByB);
        List<String> published = new ArrayList<>();
        try {
            for (int i = 1; i <= EVENT_COUNT; i++) {
                // 계약 형식 그대로 발행 — 앱 구독자도 파싱에 성공하되, 연결 없는 userId라 버린다
                String json = objectMapper.writeValueAsString(
                        new ResumeStatusChangedMessage(1L, 999_999L, "PARSED", "n-" + i).toMap());
                published.add(json);
                redisTemplate.convertAndSend(channel, json);
            }

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(receivedByA).hasSize(EVENT_COUNT);
                assertThat(receivedByB).hasSize(EVENT_COUNT);
            });
            // 나눠 받은 게 아니라 둘 다 발행한 100건을 빠짐없이, 중복 없이 받았다 (리뷰 반영: 발행 목록과 각각 비교)
            assertThat(receivedByA).containsExactlyInAnyOrderElementsOf(published);
            assertThat(receivedByB).containsExactlyInAnyOrderElementsOf(published);
        } finally {
            instanceA.destroy();
            instanceB.destroy();
        }
    }

    /** 인스턴스 하나 역할을 하는 구독 컨테이너 — 구독이 실제로 시작될 때까지 기다린 뒤 반환한다. */
    private RedisMessageListenerContainer subscribe(String channel, List<String> sink) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> sink.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(channel));
        container.afterPropertiesSet();
        container.start();
        // start() 직후엔 SUBSCRIBE가 아직 안 끝났을 수 있다 — 발행 전에 구독 완료를 보장
        await().atMost(Duration.ofSeconds(5)).until(container::isListening);
        return container;
    }
}
