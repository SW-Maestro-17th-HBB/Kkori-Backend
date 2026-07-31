package com.aisw.kkori.report.config;

import com.aisw.kkori.report.dto.ReportStatusChangedMessage;
import com.aisw.kkori.report.service.ReportStatusEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.UUID;

/**
 * 리포트 상태 이벤트 스트림 구독 컨테이너 설정 (ResumeStatusStreamConfig와 동일 구조).
 *
 * <p>Consumer Group(auto-ack)으로 구독한다. 그룹 없는 {@code ReadOffset.latest()} 구독은
 * 매 폴마다 {@code $}부터 다시 읽어 배치 처리 중 도착한 이벤트를 건너뛰기 때문에
 * (연속 XADD 시 유실), 오프셋이 전진하는 그룹 구독이 필요하다. ACK 관리는 하지 않는다
 * (auto-ack) — SSE는 유실을 허용하고 복구는 REST가 담당한다는 PRD §5 규칙.
 *
 * <p>TODO: 서버 다중 인스턴스 배포 시 인스턴스마다 별도 그룹을 쓰도록 그룹명을
 * 인스턴스 식별자 기반으로 변경해야 모든 인스턴스가 이벤트를 받는다(브로드캐스트 의미론).
 */
@Slf4j
@Configuration
public class ReportStatusStreamConfig {

    private static final String CONSUMER_GROUP = "report-sse-relay";

    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> reportStatusListenerContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            ReportStatusEventListener listener
    ) {
        createGroupIfAbsent(redisTemplate);

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofSeconds(1))
                .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        // 읽기 오류(예: Redis 순단)가 나도 구독을 취소하지 않는다 — 기본 동작은 취소라
        // 중계가 조용히 죽은 채 남는다. 오류는 로그만 남기고 다음 폴로 계속한다 (리뷰 반영).
        var readRequest = StreamMessageListenerContainer.StreamReadRequest
                .builder(StreamOffset.create(ReportStatusChangedMessage.STREAM_KEY, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(CONSUMER_GROUP, "sse-" + UUID.randomUUID()))
                .autoAcknowledge(true)
                .cancelOnError(t -> false)
                .errorHandler(t -> log.warn("리포트 상태 스트림 읽기 오류 — 구독 유지", t))
                .build();
        container.register(readRequest, listener);
        container.start();
        return container;
    }

    /** 그룹을 스트림과 함께 생성(MKSTREAM). 이미 있으면(BUSYGROUP) 무시한다 — 앱 재기동 시 항상 발생. */
    private void createGroupIfAbsent(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                connection.streamCommands().xGroupCreate(
                        ReportStatusChangedMessage.STREAM_KEY.getBytes(),
                        CONSUMER_GROUP,
                        ReadOffset.latest(),
                        true
                );
                return null;
            });
        } catch (RedisSystemException e) {
            // BUSYGROUP은 원인 체인 속(RedisBusyException)에 있으므로 체인 전체를 확인해야 한다
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    private boolean isBusyGroup(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
