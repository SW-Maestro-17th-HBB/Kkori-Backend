package com.aisw.kkori.resume.service;

import com.aisw.kkori.resume.dto.ResumeStatusEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 상태 이벤트 스트림 소비 → SSE push.
 *
 * <p>Python AI Worker가 {@code resume.parse.status.changed} 스트림에 XADD한 상태 변경을 받아
 * SSE로 중계한다. Consumer Group 없이 단순 구독한다 — SSE는 유실을 허용하고
 * 복구는 REST 상태 조회가 담당한다는 PRD §3 규칙.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeStatusEventListener implements StreamListener<String, MapRecord<String, String, String>> {

    public static final String STATUS_STREAM_KEY = "resume.parse.status.changed";

    private static final String EVENT_STATUS_CHANGED = "RESUME_ANALYSIS_STATUS_CHANGED";
    private static final String EVENT_COMPLETED = "RESUME_ANALYSIS_COMPLETED";
    private static final String EVENT_FAILED = "RESUME_ANALYSIS_FAILED";

    private final ResumeSseEmitters emitters;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        try {
            Map<String, String> value = record.getValue();
            String status = value.get("status");
            ResumeStatusEvent event = new ResumeStatusEvent(
                    Long.valueOf(value.get("resumeId")),
                    status,
                    value.get("message")
            );
            emitters.broadcast(eventNameFor(status), event);
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
