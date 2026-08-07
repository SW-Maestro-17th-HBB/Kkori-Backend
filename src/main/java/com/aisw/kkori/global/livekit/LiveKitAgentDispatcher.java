package com.aisw.kkori.global.livekit;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.service.SessionAgentDispatcher;
import io.livekit.server.AgentDispatchServiceClient;
import livekit.LivekitAgentDispatch;
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
        Response<LivekitAgentDispatch.AgentDispatch> response;
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
        // 2xx라도 생성 확인이 안 되면 실패다 — 성공 계약은 "생성된 AgentDispatch 반환"이며,
        // 빈·default 바디를 성공으로 삼으면 job이 실제로 만들어졌는지 모른 채 201을 반환하게 된다
        LivekitAgentDispatch.AgentDispatch created = response.body();
        if (created == null || created.getId().isEmpty()
                || !roomName.equals(created.getRoom())
                || !AGENT_NAME.equals(created.getAgentName())) {
            log.warn("에이전트 디스패치 응답 검증 실패 — 생성 확인 불가 (room={}, metadataBytes={})",
                    roomName, utf8Bytes(metadata));
            throw new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED);
        }
    }

    @Override
    public java.util.List<String> listDispatchIds(String roomName) {
        Response<java.util.List<LivekitAgentDispatch.AgentDispatch>> response;
        try {
            response = client.listDispatch(roomName).execute();
        } catch (IOException | RuntimeException e) {
            log.warn("dispatch 목록 조회 통신 실패 (room={}): {}", roomName, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED);
        }
        if (!response.isSuccessful() || response.body() == null) {
            log.warn("dispatch 목록 조회 실패 (room={}, http={})", roomName, response.code());
            throw new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED);
        }
        return response.body().stream().map(LivekitAgentDispatch.AgentDispatch::getId).toList();
    }

    @Override
    public void deleteDispatch(String roomName, String dispatchId) {
        Response<LivekitAgentDispatch.AgentDispatch> response;
        try {
            response = client.deleteDispatch(dispatchId, roomName).execute();
        } catch (IOException | RuntimeException e) {
            log.warn("dispatch 삭제 통신 실패 (room={}, dispatchId={}): {}",
                    roomName, dispatchId, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED);
        }
        if (!response.isSuccessful()) {
            log.warn("dispatch 삭제 실패 (room={}, dispatchId={}, http={})",
                    roomName, dispatchId, response.code());
            throw new BusinessException(ErrorCode.SESSION_DISPATCH_FAILED);
        }
    }

    private static int utf8Bytes(String metadata) {
        return metadata.getBytes(StandardCharsets.UTF_8).length;
    }
}
