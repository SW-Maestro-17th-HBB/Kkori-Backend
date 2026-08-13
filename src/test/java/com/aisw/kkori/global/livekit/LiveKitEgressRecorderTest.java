package com.aisw.kkori.global.livekit;

import livekit.LivekitEgress;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LiveKitEgressRecorder}의 HTTP 계약 검증 — MockWebServer로 LiveKit Egress API를 연기한다.
 *
 * <p>통합 테스트는 어댑터를 모킹하므로, 요청 protobuf가 PRD interview-recording.md의 실측 검증
 * 사양(audio_only·DUAL_CHANNEL_AGENT·OGG·S3 출력·자격증명 미포함)대로 실리는지는 이 테스트가
 * 유일한 자동 검증 지점이다 ({@link LiveKitAgentDispatcherTest}와 동일 구조).
 */
class LiveKitEgressRecorderTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);
    private static final String SECRET = "test-secret-at-least-thirty-two-bytes-long";

    private MockWebServer server;
    private LiveKitEgressRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String wsUrl = "ws://" + server.getHostName() + ":" + server.getPort();
        recorder = new LiveKitEgressRecorder(
                new LiveKitProperties(wsUrl, "test-key", SECRET, Duration.ofHours(1), SHORT_TIMEOUT),
                new LiveKitRecordingProperties("kkori-rec", "ap-northeast-2"));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse egressInfoResponse(String egressId) {
        Buffer body = new Buffer();
        body.write(LivekitEgress.EgressInfo.newBuilder().setEgressId(egressId).build().toByteArray());
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/protobuf")
                .setBody(body);
    }

    @Test
    @DisplayName("egressId를 반환하고, 요청 protobuf에 PRD 사양(audio_only·DUAL_CHANNEL_AGENT·OGG·S3, 자격증명 미포함)이 실린다")
    void startCarriesMeasuredSpecAndReturnsEgressId() throws Exception {
        server.enqueue(egressInfoResponse("EG_1"));

        assertThat(recorder.startRecording("room-1")).isEqualTo("EG_1");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("Egress");
        assertThat(request.getHeader("Authorization")).startsWith("Bearer ");

        LivekitEgress.RoomCompositeEgressRequest sent =
                LivekitEgress.RoomCompositeEgressRequest.parseFrom(request.getBody().readByteArray());
        assertThat(sent.getRoomName()).isEqualTo("room-1");
        assertThat(sent.getAudioOnly()).isTrue();
        assertThat(sent.getVideoOnly()).isFalse();
        assertThat(sent.getAudioMixing()).isEqualTo(LivekitEgress.AudioMixing.DUAL_CHANNEL_AGENT);
        assertThat(sent.getFileOutputsCount()).isEqualTo(1);
        LivekitEgress.EncodedFileOutput output = sent.getFileOutputs(0);
        assertThat(output.getFileType()).isEqualTo(LivekitEgress.EncodedFileType.OGG);
        assertThat(output.getFilepath()).isEqualTo("recordings/{room_name}-{time}.ogg");
        assertThat(output.getS3().getBucket()).isEqualTo("kkori-rec");
        assertThat(output.getS3().getRegion()).isEqualTo("ap-northeast-2");
        // S3 자격증명은 요청에 싣지 않는다 — egress 인스턴스의 IAM Role 사용 (PRD Egress 요청 사양)
        assertThat(output.getS3().getAccessKey()).isEmpty();
        assertThat(output.getS3().getSecret()).isEmpty();
    }

    @Test
    @DisplayName("non-2xx 응답은 예외로 끝난다 — 호출측(세션 생성)이 warn 후 진행한다")
    void non2xxThrows() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal"));

        assertThatThrownBy(() -> recorder.startRecording("room-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("2xx라도 빈(default) 바디면 egressId 확인 불가로 예외다 — 역매핑 키 없는 녹음 방치 차단")
    void emptySuccessBodyThrows() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/protobuf"));

        assertThatThrownBy(() -> recorder.startRecording("room-1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
