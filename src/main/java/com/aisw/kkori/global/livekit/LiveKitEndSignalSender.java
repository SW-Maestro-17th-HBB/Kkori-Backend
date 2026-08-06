package com.aisw.kkori.global.livekit;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.service.SessionEndSignalSender;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels.DataPacket;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LiveKit Server API SendData로 사용자 종료 신호를 발신하는 벤더 어댑터.
 *
 * <p>topic·payload는 크로스 레포 계약이다(Kkori-AI interview-end.md §3 — 임의 변경 금지):
 * topic {@code interview:end}, payload {@code {"sessionId":"<id>"}}(세션 id의 문자열화),
 * RELIABLE, 룸 브로드캐스트(대상 지정 없음). 에이전트는 발신 participant 없음(서버 API)·topic
 * 일치·sessionId 일치를 모두 확인한 뒤에만 처리한다.
 *
 * <p>타임아웃({@code livekit.api-timeout})·재시도 없음·로깅 인터셉터 없는 OkHttp 직접 공급·실패
 * 원인 미포장 방침은 {@link LiveKitRoomManager}와 같다.
 */
@Slf4j
@Component
public class LiveKitEndSignalSender implements SessionEndSignalSender {

    /** 크로스 레포 계약값 — 설정이 아닌 상수로 고정한다(agent-dispatch.md의 AGENT_NAME과 동일 방침). */
    static final String END_TOPIC = "interview:end";

    private final RoomServiceClient client;

    public LiveKitEndSignalSender(LiveKitProperties properties) {
        OkHttpClient okHttp = new OkHttpClient.Builder()
                .connectTimeout(properties.apiTimeout())
                .readTimeout(properties.apiTimeout())
                .writeTimeout(properties.apiTimeout())
                .callTimeout(properties.apiTimeout())
                .build();
        this.client = RoomServiceClient.createClient(
                properties.httpApiUrl(), properties.apiKey(), properties.apiSecret(), () -> okHttp);
    }

    @Override
    public void send(String roomName, long sessionId) {
        byte[] payload = "{\"sessionId\":\"%d\"}".formatted(sessionId).getBytes(StandardCharsets.UTF_8);
        Response<Void> response;
        try {
            response = client.sendData(roomName, payload, DataPacket.Kind.RELIABLE, List.of(), List.of(), END_TOPIC)
                    .execute();
        } catch (IOException | RuntimeException e) {
            log.warn("종료 신호 발신 통신 실패 (sessionId={}, room={}): {}",
                    sessionId, roomName, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SESSION_END_SIGNAL_FAILED);
        }
        if (!response.isSuccessful()) {
            log.warn("종료 신호 발신 실패 (sessionId={}, room={}, http={})", sessionId, roomName, response.code());
            throw new BusinessException(ErrorCode.SESSION_END_SIGNAL_FAILED);
        }
    }
}
