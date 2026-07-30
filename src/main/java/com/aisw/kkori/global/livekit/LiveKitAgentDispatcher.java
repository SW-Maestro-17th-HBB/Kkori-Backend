package com.aisw.kkori.global.livekit;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.service.SessionAgentDispatcher;
import io.livekit.server.AgentDispatchServiceClient;
import livekit.LivekitAgentDispatch.JobRestartPolicy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * LiveKit Agent Dispatch Server API로 면접관 에이전트를 명시 디스패치하는 벤더 어댑터.
 *
 * <p>재시작 정책은 {@code JRP_NEVER}를 명시한다 — 생략(3인자 오버로드)하면 protobuf 기본값
 * {@code JRP_ON_FAILURE}(=0)로 전송되어, 범위 밖(후속 상태 머신 스토리)의 자동 복구가
 * 모델링 없이 발동할 수 있다(agent-dispatch.md 기능 2).
 *
 * <p>호출에는 짧은 타임아웃({@code livekit.api-timeout})을 걸고 재시도하지 않으며, 로깅
 * 인터셉터 없는 OkHttp 직접 공급·실패 원인 미포장 방침은 {@link LiveKitRoomManager}와 같다.
 * 실패 로그에는 metadata 전문(이력서 내용 — 개인정보)을 남기지 않고 UTF-8 바이트 수만 기록한다.
 */
@Slf4j
@Component
public class LiveKitAgentDispatcher implements SessionAgentDispatcher {

    /** 크로스 레포 계약값(agent-dispatch.md 디스패치 계약) — 설정이 아닌 상수로 고정한다. */
    static final String AGENT_NAME = "kkori-interviewer";

    private final AgentDispatchServiceClient client;

    public LiveKitAgentDispatcher(LiveKitProperties properties) {
        OkHttpClient okHttp = new OkHttpClient.Builder()
                .connectTimeout(properties.apiTimeout())
                .readTimeout(properties.apiTimeout())
                .writeTimeout(properties.apiTimeout())
                .callTimeout(properties.apiTimeout())
                .build();
        this.client = AgentDispatchServiceClient.createClient(
                properties.httpApiUrl(), properties.apiKey(), properties.apiSecret(), () -> okHttp);
    }

    @Override
    public void dispatch(String roomName, String metadata) {
        Response<?> response;
        try {
            response = client.createDispatch(roomName, AGENT_NAME, metadata, JobRestartPolicy.JRP_NEVER)
                    .execute();
        } catch (IOException | RuntimeException e) {
            log.warn("에이전트 디스패치 통신 실패 (room={}, metadataBytes={}): {}",
                    roomName, utf8Bytes(metadata), e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED);
        }
        if (!response.isSuccessful()) {
            log.warn("에이전트 디스패치 실패 (room={}, http={}, metadataBytes={})",
                    roomName, response.code(), utf8Bytes(metadata));
            throw new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED);
        }
    }

    private static int utf8Bytes(String metadata) {
        return metadata.getBytes(StandardCharsets.UTF_8).length;
    }
}
