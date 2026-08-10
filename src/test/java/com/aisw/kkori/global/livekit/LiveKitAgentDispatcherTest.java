package com.aisw.kkori.global.livekit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import livekit.LivekitAgentDispatch;
import okhttp3.mockwebserver.MockResponse;
import okio.Buffer;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LiveKitAgentDispatcher}의 HTTP 계약 검증 — MockWebServer로 LiveKit Server API를 연기한다.
 *
 * <p>통합 테스트는 어댑터를 모킹하므로, 요청 protobuf(agent_name·restart_policy 포함)와 실패
 * 매핑(S004)·로그 방침(metadata 전문 미노출)은 이 테스트가 유일한 자동 검증 지점이다
 * ({@link LiveKitRoomManagerTest}와 동일 구조).
 */
class LiveKitAgentDispatcherTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);
    private static final String SECRET = "test-secret-at-least-thirty-two-bytes-long";
    private static final String METADATA =
            "{\"sessionId\":\"1\",\"interviewType\":\"THIRTY_MIN\",\"position\":\"BACKEND\","
                    + "\"resumeContext\":\"민감한 이력서 본문\"}";

    private MockWebServer server;
    private LiveKitAgentDispatcher dispatcher;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // MockWebServer는 http만 지원 — ws URL을 넣어 httpApiUrl() 파생(ws→http)이 실제 요청에 쓰이는지도 함께 검증한다
        String wsUrl = "ws://" + server.getHostName() + ":" + server.getPort();
        dispatcher = new LiveKitAgentDispatcher(new LiveKitProperties(
                wsUrl, "test-key", SECRET, Duration.ofHours(1), SHORT_TIMEOUT));

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(LiveKitAgentDispatcher.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() throws Exception {
        ((Logger) LoggerFactory.getLogger(LiveKitAgentDispatcher.class)).detachAppender(logAppender);
        server.shutdown();
    }

    /** 성공 계약대로 "생성된 AgentDispatch"를 담은 2xx 응답 — 어댑터의 응답 검증(id·room·agent_name) 대상. */
    private static MockResponse createdDispatchResponse(String roomName) {
        LivekitAgentDispatch.AgentDispatch created = LivekitAgentDispatch.AgentDispatch.newBuilder()
                .setId("AD_test")
                .setRoom(roomName)
                .setAgentName("kkori-interviewer")
                .build();
        Buffer body = new Buffer();
        body.write(created.toByteArray());
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/protobuf")
                .setBody(body);
    }

    @Test
    @DisplayName("생성된 AgentDispatch 응답이면 성공하고, 요청 protobuf에 room·agent_name·metadata·JRP_NEVER가 실린다")
    void dispatchSucceedsAndRequestCarriesContract() throws Exception {
        server.enqueue(createdDispatchResponse("room-1"));

        assertThatCode(() -> dispatcher.dispatch("room-1", METADATA)).doesNotThrowAnyException();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("AgentDispatchService");
        assertThat(request.getHeader("Authorization")).startsWith("Bearer ");

        // 계약 검증은 요청 protobuf 수준까지 — 재시작 정책을 생략하면 기본값 JRP_ON_FAILURE로
        // 전송되어 범위 밖의 자동 복구가 발동한다 (agent-dispatch.md 기능 2)
        LivekitAgentDispatch.CreateAgentDispatchRequest sent =
                LivekitAgentDispatch.CreateAgentDispatchRequest.parseFrom(request.getBody().readByteArray());
        assertThat(sent.getRoom()).isEqualTo("room-1");
        assertThat(sent.getAgentName()).isEqualTo("kkori-interviewer");
        assertThat(sent.getMetadata()).isEqualTo(METADATA);
        assertThat(sent.getRestartPolicy()).isEqualTo(LivekitAgentDispatch.JobRestartPolicy.JRP_NEVER);
    }

    @Test
    @DisplayName("2xx라도 빈(default) 바디면 생성 확인 불가로 S004에 매핑된다")
    void dispatchMapsEmptySuccessBodyToS004() {
        // 빈 바디는 모든 필드가 기본값인 AgentDispatch로 역직렬화된다 — 성공 계약은 "생성된
        // AgentDispatch 반환"이므로 id·room·agent_name이 확인되지 않으면 실패로 다룬다
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/protobuf"));

        assertThatThrownBy(() -> dispatcher.dispatch("room-1", METADATA))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_DISPATCH_FAILED));
    }

    @Test
    @DisplayName("2xx라도 역직렬화 불가능한 손상 응답이면 S004로 매핑된다")
    void dispatchMapsMalformedBodyToS004() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/protobuf")
                .setBody("not-a-protobuf-message"));

        assertThatThrownBy(() -> dispatcher.dispatch("room-1", METADATA))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_DISPATCH_FAILED));
    }

    @Test
    @DisplayName("non-2xx 응답은 S004로 매핑된다")
    void dispatchMapsNon2xxToS004() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal"));

        assertThatThrownBy(() -> dispatcher.dispatch("room-1", METADATA))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_DISPATCH_FAILED));
    }

    @Test
    @DisplayName("응답 지연이 api-timeout을 넘으면 S004로 매핑된다 (요청 스레드 지연 상한)")
    void dispatchMapsTimeoutToS004() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        long started = System.nanoTime();
        assertThatThrownBy(() -> dispatcher.dispatch("room-1", METADATA))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_DISPATCH_FAILED));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // 타임아웃 설정이 실제로 적용됐는지 — 무기한 대기가 아니라 설정값 근처에서 끊겨야 한다
        assertThat(elapsed).isLessThan(SHORT_TIMEOUT.multipliedBy(6));
    }

    @Test
    @DisplayName("listDispatchIds — 응답의 dispatch id 목록을 반환하고, 요청 protobuf에 room이 실린다")
    void listDispatchIdsCarriesRoomAndReturnsIds() throws Exception {
        LivekitAgentDispatch.ListAgentDispatchResponse listed =
                LivekitAgentDispatch.ListAgentDispatchResponse.newBuilder()
                        .addAgentDispatches(LivekitAgentDispatch.AgentDispatch.newBuilder().setId("AD_1"))
                        .addAgentDispatches(LivekitAgentDispatch.AgentDispatch.newBuilder().setId("AD_2"))
                        .build();
        Buffer body = new Buffer();
        body.write(listed.toByteArray());
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/protobuf").setBody(body));

        assertThat(dispatcher.listDispatchIds("room-1")).containsExactly("AD_1", "AD_2");

        LivekitAgentDispatch.ListAgentDispatchRequest sent =
                LivekitAgentDispatch.ListAgentDispatchRequest.parseFrom(
                        server.takeRequest().getBody().readByteArray());
        assertThat(sent.getRoom()).isEqualTo("room-1");
    }

    @Test
    @DisplayName("deleteDispatch — 요청 protobuf의 room·dispatch_id 필드가 각자 제자리에 실린다 (인자 순서 회귀 고정)")
    void deleteDispatchCarriesFieldsInRightPlaces() throws Exception {
        server.enqueue(createdDispatchResponse("room-1"));

        assertThatCode(() -> dispatcher.deleteDispatch("room-1", "AD_target")).doesNotThrowAnyException();

        // SDK 시그니처는 deleteDispatch(room, dispatchId) — getDispatch(id, room)과 순서가 달라
        // 뒤바꾸면 room에 dispatch id가 실려 삭제가 항상 실패한다(재디스패치 CAS만 소진되는 병리)
        LivekitAgentDispatch.DeleteAgentDispatchRequest sent =
                LivekitAgentDispatch.DeleteAgentDispatchRequest.parseFrom(
                        server.takeRequest().getBody().readByteArray());
        assertThat(sent.getRoom()).isEqualTo("room-1");
        assertThat(sent.getDispatchId()).isEqualTo("AD_target");
    }

    @Test
    @DisplayName("list·delete의 non-2xx 응답은 S004로 매핑된다 — 호출측(재디스패치)은 생성을 포기한다")
    void listAndDeleteMapNon2xxToS004() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal"));
        assertThatThrownBy(() -> dispatcher.listDispatchIds("room-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_DISPATCH_FAILED));

        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        assertThatThrownBy(() -> dispatcher.deleteDispatch("room-1", "AD_target"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_DISPATCH_FAILED));
    }

    @Test
    @DisplayName("실패 로그에 metadata 전문·API Secret은 없고, UTF-8 바이트 수는 기록된다")
    void failureLogCarriesByteCountButNeverPayloadOrSecret() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal"));

        assertThatThrownBy(() -> dispatcher.dispatch("room-1", METADATA))
                .isInstanceOf(BusinessException.class);

        int expectedBytes = METADATA.getBytes(StandardCharsets.UTF_8).length;
        assertThat(logAppender.list).isNotEmpty().allSatisfy(event -> {
            String logged = event.getFormattedMessage();
            assertThat(logged).doesNotContain("민감한 이력서 본문").doesNotContain(METADATA);
            assertThat(logged).doesNotContain(SECRET);
        });
        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("metadataBytes=" + expectedBytes));
    }
}
