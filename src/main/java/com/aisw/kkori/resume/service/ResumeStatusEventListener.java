package com.aisw.kkori.resume.service;

import com.aisw.kkori.resume.dto.ResumeStatusChangedMessage;
import com.aisw.kkori.resume.dto.ResumeStatusEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * 상태 이벤트 스트림 소비 → SSE push — {@code resume.parse.status.changed} 스트림의 유일한 소비 창구.
 *
 * <p>메시지 스키마는 {@link ResumeStatusChangedMessage} 참조. Consumer Group(auto-ack)으로
 * 구독하되 ACK 관리는 하지 않는다 — SSE는 유실을 허용하고 복구는 REST가 담당한다는 PRD §3 규칙.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeStatusEventListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final String EVENT_STATUS_CHANGED = "RESUME_ANALYSIS_STATUS_CHANGED";
    private static final String EVENT_COMPLETED = "RESUME_ANALYSIS_COMPLETED";
    private static final String EVENT_FAILED = "RESUME_ANALYSIS_FAILED";

    private final ResumeSseEmitters emitters;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        try {
            ResumeStatusChangedMessage message = ResumeStatusChangedMessage.from(record.getValue());
            ResumeStatusEvent event = new ResumeStatusEvent(
                    message.resumeId(), message.status(), message.message());
            // 소유자에게만 전송 — userId는 Worker가 분석 요청 메시지에서 에코한 값 (계약)
            emitters.sendTo(message.userId(), eventNameFor(message.status()), event);
        } catch (RuntimeException e) {
            // 잘못된 이벤트 하나가 리스너를 죽이지 않도록 삼킨다
            log.warn("상태 이벤트 처리 실패: {}", record.getValue(), e);
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
