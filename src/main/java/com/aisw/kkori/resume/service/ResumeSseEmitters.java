package com.aisw.kkori.resume.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 연결 레지스트리.
 *
 * <p>TODO: 인증 도메인 완성 시 userId 키 기반 라우팅으로 교체 —
 * 현재는 연결을 구분할 사용자 정보가 없어 전체 브로드캐스트로 동작한다.
 */
@Slf4j
@Component
public class ResumeSseEmitters {

    /** 연결 유지 최대 시간. 만료 시 브라우저 EventSource가 자동 재연결한다. */
    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter add() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // 끊긴 연결 — 콜백으로도 제거되지만 전송 실패 시점에 즉시 정리
                emitters.remove(emitter);
            }
        }
    }

    /** 프록시/ALB의 유휴 연결 종료를 막는 keepalive 주석(":ping"). 데이터가 아니라 프론트 핸들러에 걸리지 않는다. */
    @Scheduled(fixedRate = 20_000)
    public void sendKeepalive() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    int connectionCount() {
        return emitters.size();
    }
}
