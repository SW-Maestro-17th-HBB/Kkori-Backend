package com.aisw.kkori.global.livekit;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LiveKitRoomManager}의 HTTP 계약 검증 — MockWebServer로 LiveKit Server API를 연기한다.
 *
 * <p>통합 테스트는 어댑터를 모킹하므로, 실패 매핑(S002)·타임아웃·quiet 계약은 이 테스트가
 * 유일한 자동 검증 지점이다(실 Cloud 수동 검증은 정상 경로만 커버).
 */
class LiveKitRoomManagerTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);

    private MockWebServer server;
    private LiveKitRoomManager roomManager;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // MockWebServer는 http만 지원 — ws URL을 넣어 httpApiUrl() 파생(ws→http)이 실제 요청에 쓰이는지도 함께 검증한다
        String wsUrl = "ws://" + server.getHostName() + ":" + server.getPort();
        roomManager = new LiveKitRoomManager(new LiveKitProperties(
                wsUrl, "test-key", "test-secret-at-least-thirty-two-bytes-long",
                Duration.ofHours(1), SHORT_TIMEOUT));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("2xx 응답이면 룸 생성이 성공하고 요청은 파생된 HTTP 엔드포인트의 Twirp 경로로 나간다")
    void createRoomSucceedsOn2xx() throws Exception {
        // SDK는 응답을 protobuf로 역직렬화한다 — 빈 바디는 모든 필드가 기본값인 유효한 Room 메시지다
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/protobuf"));

        assertThatCode(() -> roomManager.createRoom("room-1")).doesNotThrowAnyException();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("RoomService");
        assertThat(request.getHeader("Authorization")).startsWith("Bearer ");
    }

    @Test
    @DisplayName("2xx라도 역직렬화 불가능한 손상 응답이면 S002로 매핑된다")
    void createRoomMapsMalformedBodyToS002() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/protobuf")
                .setBody("not-a-protobuf-message"));

        assertThatThrownBy(() -> roomManager.createRoom("room-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_ROOM_CREATE_FAILED));
    }

    @Test
    @DisplayName("non-2xx 응답은 S002로 매핑된다")
    void createRoomMapsNon2xxToS002() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal"));

        assertThatThrownBy(() -> roomManager.createRoom("room-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_ROOM_CREATE_FAILED));
    }

    @Test
    @DisplayName("응답 지연이 api-timeout을 넘으면 S002로 매핑된다 (잠금 보유 시간 상한)")
    void createRoomMapsTimeoutToS002() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        long started = System.nanoTime();
        assertThatThrownBy(() -> roomManager.createRoom("room-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_ROOM_CREATE_FAILED));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // 타임아웃 설정이 실제로 적용됐는지 — 무기한 대기가 아니라 설정값 근처에서 끊겨야 한다
        assertThat(elapsed).isLessThan(SHORT_TIMEOUT.multipliedBy(6));
    }

    @Test
    @DisplayName("삭제는 non-2xx·통신 실패 모두 삼킨다 (never-throw 계약)")
    void deleteRoomQuietlyNeverThrows() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        assertThatCode(() -> roomManager.deleteRoomQuietly("room-missing")).doesNotThrowAnyException();

        server.shutdown();   // 통신 자체가 불가능한 상황
        assertThatCode(() -> roomManager.deleteRoomQuietly("room-unreachable")).doesNotThrowAnyException();
    }
}
