package com.aisw.kkori.global.livekit;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.dto.AgentPresence;
import com.aisw.kkori.session.dto.RoomPresence;
import com.aisw.kkori.session.service.SessionRoomManager;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels.ParticipantInfo;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * LiveKit Server API로 룸을 생성·삭제하는 벤더 어댑터.
 *
 * <p>호출에는 짧은 타임아웃({@code livekit.api-timeout})을 걸고 재시도하지 않는다 — 호출은
 * 트랜잭션·잠금 밖(커밋 후)에서 일어나며, 타임아웃은 요청 스레드가 LiveKit 지연에 붙잡히는
 * 시간의 상한이다.
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

    @Override
    public AgentPresence probeAgentPresence(String roomName) {
        Response<List<ParticipantInfo>> response;
        try {
            response = client.listParticipants(roomName).execute();
        } catch (Exception e) {
            // never-throw 계약 — 조회 실패는 UNKNOWN(호출측이 이번 회차를 건너뛴다)
            log.warn("LiveKit 참가자 조회 통신 실패 (room={}): {}", roomName, e.getClass().getSimpleName());
            return AgentPresence.unknown();
        }
        if (response.code() == 404) {
            // twirp not_found — 룸 미존재는 "진행 중 면접 아님"의 확정 증거다
            return AgentPresence.absent();
        }
        if (!response.isSuccessful() || response.body() == null) {
            log.warn("LiveKit 참가자 조회 실패 (room={}, http={})", roomName, response.code());
            return AgentPresence.unknown();
        }
        return response.body().stream()
                .filter(p -> p.getKind() == ParticipantInfo.Kind.AGENT)
                .findFirst()
                // joined_at은 unix 초 — 0(미설정)이면 호출측이 현재 시각으로 보수 적용한다
                .map(p -> AgentPresence.present(p.getJoinedAt() > 0 ? Instant.ofEpochSecond(p.getJoinedAt()) : null))
                .orElseGet(AgentPresence::absent);
    }

    @Override
    public RoomPresence probeRoomPresence(String roomName, String candidateIdentity) {
        Response<List<ParticipantInfo>> response;
        try {
            response = client.listParticipants(roomName).execute();
        } catch (Exception e) {
            // never-throw 계약 — 조회 실패는 observed=false(판정은 호출측 몫)
            log.warn("LiveKit 참가자 조회 통신 실패 (room={}): {}", roomName, e.getClass().getSimpleName());
            return RoomPresence.unknown();
        }
        if (response.code() == 404) {
            // twirp not_found — 룸 미존재는 둘 다 부재인 확정 관측이다
            return RoomPresence.of(false, false, null);
        }
        if (!response.isSuccessful() || response.body() == null) {
            log.warn("LiveKit 참가자 조회 실패 (room={}, http={})", roomName, response.code());
            return RoomPresence.unknown();
        }
        List<ParticipantInfo> participants = response.body();
        Instant agentJoinedAt = participants.stream()
                .filter(p -> p.getKind() == ParticipantInfo.Kind.AGENT)
                .findFirst()
                .map(p -> p.getJoinedAt() > 0 ? Instant.ofEpochSecond(p.getJoinedAt()) : null)
                .orElse(null);
        boolean agentPresent = participants.stream().anyMatch(p -> p.getKind() == ParticipantInfo.Kind.AGENT);
        boolean candidatePresent = participants.stream().anyMatch(p -> candidateIdentity.equals(p.getIdentity()));
        return RoomPresence.of(agentPresent, candidatePresent, agentJoinedAt);
    }
}
