package com.aisw.kkori.global.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 연결 레지스트리 — userId 키로 사용자별 라우팅한다. 도메인 지식이 없는 공용 부품이며,
 * 도메인은 이 클래스를 상속한 빈(예: {@code ResumeSseEmitters})으로 자기 채널의 연결을 관리한다.
 *
 * <p>이벤트는 소유자의 연결에만 전송된다(타 사용자의 상태 노출 차단).
 * 같은 사용자의 다중 연결(여러 탭)을 허용하므로 값은 Set이다.
 *
 * <p>채널(엔드포인트)은 현재 도메인마다 하나다(resumes·reports) — 사용자 단위 SSE 채널이
 * 3개 도메인째 필요해지면 단일 이벤트 채널로의 통합을 재검토한다(리포트 PRD §5 제약,
 * 브라우저 동시 연결 한도 대비).
 */
@Slf4j
public abstract class UserSseEmitters {

    /** 연결 유지 최대 시간. 만료 시 클라이언트(fetch 기반 SSE)가 재연결한다. */
    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final Map<Long, Set<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter add(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        // compute()로 추가를 키 단위 원자화 — remove()의 빈 Set 정리와 경합해도 새 연결이 유실되지 않는다
        emittersByUser.compute(userId, (key, emitters) -> {
            Set<SseEmitter> set = (emitters != null) ? emitters : ConcurrentHashMap.newKeySet();
            set.add(emitter);
            return set;
        });
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
        return emitter;
    }

    /** 해당 사용자의 연결에만 전송한다. 연결이 없으면 조용히 버린다(유실 허용 — 복구는 REST). */
    public void sendTo(Long userId, String eventName, Object data) {
        Set<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // 끊긴 연결 — 콜백으로도 제거되지만 전송 실패 시점에 즉시 정리
                remove(userId, emitter);
            }
        }
    }

    /** 프록시/ALB의 유휴 연결 종료를 막는 keepalive 주석(":ping"). 데이터가 아니라 프론트 핸들러에 걸리지 않는다. */
    @Scheduled(fixedRate = 20_000)
    public void sendKeepalive() {
        emittersByUser.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    remove(userId, emitter);
                }
            }
        });
    }

    private void remove(Long userId, SseEmitter emitter) {
        // compute()로 제거·빈 Set 정리를 키 단위 원자화 — add()와 직렬화되어
        // "비었다고 판단한 사이 새 emitter가 추가된 Set을 통째로 제거"하는 유실이 없다
        emittersByUser.compute(userId, (key, emitters) -> {
            if (emitters == null) {
                return null;
            }
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    protected int connectionCount() {
        return emittersByUser.values().stream().mapToInt(Set::size).sum();
    }
}
