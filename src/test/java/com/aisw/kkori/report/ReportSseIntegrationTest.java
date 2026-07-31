package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.report.dto.ReportStatusChangedMessage;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repository.UserRepository;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 리포트 SSE 경계 계약 테스트 — Worker가 할 행위(상태 스트림 XADD)를 그대로 수행해
 * SSE 수신까지의 중계와 사용자별 라우팅(타인 이벤트 미수신)을 검증한다.
 * 실제 JWT 인증을 사용한다 (필터가 User 존재까지 확인하므로 실제 레코드 필요).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReportSseIntegrationTest {

    @LocalServerPort int port;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired UserRepository userRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("상태 이벤트가 소유자의 SSE로만 타입 분기되어 수신된다 (타인 이벤트는 미수신)")
    void statusEvents_areRoutedToOwnerOnly() throws Exception {
        User owner = userRepository.save(User.create("report-sse-test-provider", null, "테스터"));
        String accessToken = jwtTokenProvider.createAccessToken(owner.getId());
        long foreignUserId = owner.getId() + 999;

        List<String> lines = new CopyOnWriteArrayList<>();

        // try-with-resources 금지: HttpClient.close()는 진행 중인 요청 완료를 기다리는데
        // SSE 스트림은 끝나지 않으므로 무한 대기에 빠진다 → shutdownNow()로 즉시 종료
        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/sse/v1/reports"))
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + accessToken)
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

            // 타인 이벤트를 먼저 발행 — 이후 소유자 이벤트 3건이 다 도착했는데도 이게 없으면 격리 성공
            publishStatus(777, foreignUserId, "PROCESSING", "남의 리포트 이벤트");
            publishStatus(12, owner.getId(), "PROCESSING", "");
            publishStatus(12, owner.getId(), "COMPLETED", "");
            publishStatus(34, owner.getId(), "FAILED", "재전달 임계 초과");

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                String received = String.join("\n", lines);
                assertThat(received).contains("event:REPORT_GENERATION_STATUS_CHANGED");
                assertThat(received).contains("event:REPORT_GENERATION_COMPLETED");
                assertThat(received).contains("event:REPORT_GENERATION_FAILED");
                assertThat(received).contains("\"reportId\":12");
                assertThat(received).contains("\"status\":\"COMPLETED\"");
                assertThat(received).contains("재전달 임계 초과");
            });

            // 사용자별 라우팅 — 타인의 이벤트는 이 연결로 오지 않는다
            String received = String.join("\n", lines);
            assertThat(received).doesNotContain("\"reportId\":777");
            assertThat(received).doesNotContain("남의 리포트 이벤트");
        } finally {
            client.shutdownNow();
        }
    }

    /** Worker가 할 발행을 계약 record 그대로 수행한다 — toMap()이 실제 직렬화 규칙. */
    private void publishStatus(long reportId, long userId, String status, String message) {
        redisTemplate.opsForStream().add(StreamRecords
                .mapBacked(new ReportStatusChangedMessage(reportId, userId, status, message).toMap())
                .withStreamKey(ReportStatusChangedMessage.STREAM_KEY));
    }
}
