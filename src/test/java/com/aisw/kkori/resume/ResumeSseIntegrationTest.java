package com.aisw.kkori.resume;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.resume.dto.ResumeStatusChangedMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SSE 경계 계약 테스트 — Worker가 할 행위(상태 스트림 XADD)를 그대로 수행해
 * SSE 수신까지의 중계를 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ResumeSseIntegrationTest {

    @LocalServerPort int port;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("상태 스트림에 이벤트를 넣으면 SSE로 타입 분기되어 수신된다")
    void statusEvents_arePushedToSseSubscribers() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();

        // try-with-resources 금지: HttpClient.close()는 진행 중인 요청 완료를 기다리는데
        // SSE 스트림은 끝나지 않으므로 무한 대기에 빠진다 → shutdownNow()로 즉시 종료
        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/sse/v1/resumes"))
                    .header("Accept", "text/event-stream")
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.fromLineSubscriber(
                    new java.util.concurrent.Flow.Subscriber<String>() {
                        @Override
                        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(String item) {
                            lines.add(item);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                        }

                        @Override
                        public void onComplete() {
                        }
                    }));

            // SSE 연결과 스트림 리스너 폴링이 자리잡을 시간
            Thread.sleep(1500);

            publishStatus(12, "TEXT_EXTRACTING", "");
            publishStatus(12, "EMBEDDED", "");
            publishStatus(34, "FAILED", "PDF 텍스트 추출 실패");

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                String received = String.join("\n", lines);
                assertThat(received).contains("event:RESUME_ANALYSIS_STATUS_CHANGED");
                assertThat(received).contains("event:RESUME_ANALYSIS_COMPLETED");
                assertThat(received).contains("event:RESUME_ANALYSIS_FAILED");
                assertThat(received).contains("\"resumeId\":12");
                assertThat(received).contains("\"status\":\"EMBEDDED\"");
                assertThat(received).contains("PDF 텍스트 추출 실패");
            });
        } finally {
            client.shutdownNow();
        }
    }

    /** Worker가 할 발행을 계약 record 그대로 수행한다 — toMap()이 실제 직렬화 규칙. */
    private void publishStatus(long resumeId, String status, String message) {
        redisTemplate.opsForStream().add(StreamRecords
                .mapBacked(new ResumeStatusChangedMessage(resumeId, status, message).toMap())
                .withStreamKey(ResumeStatusChangedMessage.STREAM_KEY));
    }
}
