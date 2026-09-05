package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.sse.StatusChannelListener;
import com.aisw.kkori.resume.dto.ResumeStatusChangedMessage;
import com.aisw.kkori.resume.dto.ResumeStatusEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 상태 이벤트 채널 구독 → SSE push — {@code resume.parse.status.changed} 채널의 유일한 구독 창구.
 *
 * <p>메시지 스키마는 {@link ResumeStatusChangedMessage} 참조. Worker가 JSON 문자열로 PUBLISH한 것을
 * 필드 맵으로 읽어 계약 record로 변환한다. Pub/Sub은 구독 중인 모든 인스턴스에 전달되므로 각 인스턴스는
 * 자기 메모리의 SSE 연결에만 보내고, 연결이 없으면 버린다 — SSE는 유실을 허용하고 복구는 REST가
 * 담당한다는 PRD §3 규칙.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeStatusEventListener implements StatusChannelListener {

    private static final String EVENT_STATUS_CHANGED = "RESUME_ANALYSIS_STATUS_CHANGED";
    private static final String EVENT_COMPLETED = "RESUME_ANALYSIS_COMPLETED";
    private static final String EVENT_FAILED = "RESUME_ANALYSIS_FAILED";
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {
    };

    private final ResumeSseEmitters emitters;
    private final ObjectMapper objectMapper;

    @Override
    public String channel() {
        return ResumeStatusChangedMessage.CHANNEL;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            ResumeStatusChangedMessage status = ResumeStatusChangedMessage.from(objectMapper.readValue(body, FIELDS));
            ResumeStatusEvent event = new ResumeStatusEvent(
                    status.resumeId(), status.status(), status.message());
            // 소유자에게만 전송 — userId는 Worker가 분석 요청 메시지에서 에코한 값 (계약)
            emitters.sendTo(status.userId(), eventNameFor(status.status()), event);
        } catch (Exception e) {
            // 잘못된 이벤트 하나가 리스너를 죽이지 않도록 삼킨다
            log.warn("상태 이벤트 처리 실패: {}", body, e);
        }
    }

    private String eventNameFor(String status) {
        return switch (status) {
            case "EMBEDDED" -> EVENT_COMPLETED;
            case "FAILED" -> EVENT_FAILED;
            default -> EVENT_STATUS_CHANGED;
        };
    }
}
