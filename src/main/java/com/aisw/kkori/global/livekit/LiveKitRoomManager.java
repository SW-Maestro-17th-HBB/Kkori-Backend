package com.aisw.kkori.global.livekit;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.service.SessionRoomManager;
import io.livekit.server.RoomServiceClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;

/**
 * LiveKit Server API로 룸을 생성·삭제하는 벤더 어댑터.
 *
 * <p>호출에는 짧은 타임아웃({@code livekit.api-timeout})을 걸고 재시도하지 않는다 — 룸 생성은
 * user 행 잠금을 보유한 채 일어나는 외부 왕복이므로 타임아웃이 잠금 보유 시간의 상한이다.
 * 로깅 인터셉터 없는 OkHttp를 직접 공급하므로 SDK가 요청(Authorization 헤더 — API Secret
 * 파생)을 로그에 남기지 않는다. 실패 예외의 원인을 감싸지 않는 것도 같은 유출 방지
 * 방침이다({@link LiveKitTokenIssuer}와 동일).
 */
@Slf4j
@Component
public class LiveKitRoomManager implements SessionRoomManager {

    private final RoomServiceClient client;

    public LiveKitRoomManager(LiveKitProperties properties) {
        OkHttpClient okHttp = new OkHttpClient.Builder()
                .connectTimeout(properties.apiTimeout())
                .readTimeout(properties.apiTimeout())
                .writeTimeout(properties.apiTimeout())
                .callTimeout(properties.apiTimeout())
                .build();
        // 마지막 boolean은 로깅이 아니라 리전 failover 스위치다 — 기본값(true) 오버로드를 써서 Cloud failover를 유지한다
        this.client = RoomServiceClient.createClient(
                properties.httpApiUrl(), properties.apiKey(), properties.apiSecret(), () -> okHttp);
    }

    @Override
    public void createRoom(String roomName) {
        Response<?> response;
        try {
            response = client.createRoom(roomName).execute();
        } catch (IOException | RuntimeException e) {
            // IOException은 타임아웃 포함(응답만 유실됐을 수 있음 — 보상 삭제는 호출측 동기화 담당),
            // RuntimeException은 손상 응답의 역직렬화 실패 등 — 모두 생성 실패로 수렴한다
            log.warn("LiveKit 룸 생성 통신 실패 (room={}): {}", roomName, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SESSION_ROOM_CREATE_FAILED);
        }
        if (!response.isSuccessful()) {
            log.warn("LiveKit 룸 생성 실패 (room={}, http={})", roomName, response.code());
            throw new BusinessException(ErrorCode.SESSION_ROOM_CREATE_FAILED);
        }
    }

    @Override
    public void deleteRoomQuietly(String roomName) {
        try {
            Response<Void> response = client.deleteRoom(roomName).execute();
            if (!response.isSuccessful()) {
                log.warn("LiveKit 룸 삭제 실패 — empty timeout 자연 소멸 대기 (room={}, http={})",
                        roomName, response.code());
            }
        } catch (Exception e) {
            // never-throw 계약 — 정리·보상 실패가 응답·커밋 결과에 영향을 주면 안 된다
            log.warn("LiveKit 룸 삭제 통신 실패 — empty timeout 자연 소멸 대기 (room={}): {}",
                    roomName, e.getClass().getSimpleName());
        }
    }
}
