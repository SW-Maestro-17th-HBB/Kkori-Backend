package com.aisw.kkori.global.livekit;

import com.aisw.kkori.session.service.SessionRecorder;
import io.livekit.server.AudioMixing;
import io.livekit.server.EgressServiceClient;
import livekit.LivekitEgress;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;

/**
 * LiveKit Egress Server API로 룸 오디오 녹음(RoomComposite, audio-only)을 시작하는 벤더 어댑터
 * (PRD interview-recording.md Egress 요청 사양 — 2026-08-11 실측 검증 구성).
 *
 * <p>{@code DUAL_CHANNEL_AGENT}로 에이전트/지원자를 좌/우 채널로 분리해 세션당 파일 1개를
 * 유지한다(재연결에도 파일 미분열 — ParticipantEgress 배제 근거는 PRD). S3 자격증명은 요청에
 * 싣지 않는다 — egress 인스턴스의 IAM Role을 사용한다. {@code filepath}는 템플릿일 뿐이며
 * 실제 objectKey는 {@code egress_ended} webhook의 {@code fileResults}에서 읽는다.
 *
 * <p>호출에는 짧은 타임아웃({@code livekit.api-timeout})을 걸고 재시도하지 않으며, 로깅
 * 인터셉터 없는 OkHttp 직접 공급·실패 원인 미포장 방침은 {@link LiveKitRoomManager}와 같다.
 * 실패는 {@code ErrorCode} 없이 던진다 — 녹음은 부가 기능이라 호출측이 삼키고 진행한다
 * ({@link SessionRecorder} 계약).
 */
@Slf4j
@Component
public class LiveKitEgressRecorder implements SessionRecorder {

    /** 실측 검증된 출력 경로 템플릿(PRD Egress 요청 사양) — 파일명 규칙과의 결합은 없다(웹훅이 원천). */
    static final String FILEPATH_TEMPLATE = "recordings/{room_name}-{time}.ogg";

    private final EgressServiceClient client;
    private final LiveKitRecordingProperties recordingProperties;

    public LiveKitEgressRecorder(LiveKitProperties properties, LiveKitRecordingProperties recordingProperties) {
        OkHttpClient okHttp = new OkHttpClient.Builder()
                .connectTimeout(properties.apiTimeout())
                .readTimeout(properties.apiTimeout())
                .writeTimeout(properties.apiTimeout())
                .callTimeout(properties.apiTimeout())
                .build();
        this.client = EgressServiceClient.createClient(
                properties.httpApiUrl(), properties.apiKey(), properties.apiSecret(), () -> okHttp);
        this.recordingProperties = recordingProperties;
    }

    @Override
    public String startRecording(String roomName) {
        LivekitEgress.EncodedFileOutput output = LivekitEgress.EncodedFileOutput.newBuilder()
                .setFileType(LivekitEgress.EncodedFileType.OGG)
                .setFilepath(FILEPATH_TEMPLATE)
                .setS3(LivekitEgress.S3Upload.newBuilder()
                        .setRegion(recordingProperties.region())
                        .setBucket(recordingProperties.bucket()))
                .build();
        Response<LivekitEgress.EgressInfo> response;
        try {
            response = client.startRoomCompositeEgress(roomName, output, "", null, null,
                    true, false, "", AudioMixing.DUAL_CHANNEL_AGENT).execute();
        } catch (IOException | RuntimeException e) {
            log.warn("egress 시작 통신 실패 (room={}): {}", roomName, e.getClass().getSimpleName());
            throw new IllegalStateException("egress 시작 통신 실패 (room=%s)".formatted(roomName));
        }
        if (!response.isSuccessful()) {
            log.warn("egress 시작 실패 (room={}, http={})", roomName, response.code());
            throw new IllegalStateException("egress 시작 실패 (room=%s)".formatted(roomName));
        }
        // 2xx라도 egressId가 확인되지 않으면 실패다 — 빈·default 바디를 성공으로 삼으면
        // webhook 역매핑 키 없는 녹음이 시작된 채로 방치된다 (디스패치 어댑터와 동일 방침)
        LivekitEgress.EgressInfo info = response.body();
        if (info == null || info.getEgressId().isEmpty()) {
            log.warn("egress 시작 응답 검증 실패 — egressId 확인 불가 (room={})", roomName);
            throw new IllegalStateException("egress 시작 응답 검증 실패 (room=%s)".formatted(roomName));
        }
        return info.getEgressId();
    }
}
