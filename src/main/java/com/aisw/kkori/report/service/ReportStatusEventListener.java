package com.aisw.kkori.report.service;

import com.aisw.kkori.report.dto.ReportStatusChangedMessage;
import com.aisw.kkori.report.dto.ReportStatusEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * 상태 이벤트 스트림 소비 → SSE push — {@code report.status.changed} 스트림의 유일한 소비 창구.
 *
 * <p>메시지 스키마는 {@link ReportStatusChangedMessage} 참조. Consumer Group(auto-ack)으로
 * 구독하되 ACK 관리는 하지 않는다 — SSE는 유실을 허용하고 복구는 REST가 담당한다는 PRD §5 규칙.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportStatusEventListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final String EVENT_STATUS_CHANGED = "REPORT_GENERATION_STATUS_CHANGED";
    private static final String EVENT_COMPLETED = "REPORT_GENERATION_COMPLETED";
    private static final String EVENT_FAILED = "REPORT_GENERATION_FAILED";

    private final ReportSseEmitters emitters;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        try {
            ReportStatusChangedMessage message = ReportStatusChangedMessage.from(record.getValue());
            ReportStatusEvent event = new ReportStatusEvent(
                    message.reportId(), message.status(), message.message());
            // 소유자에게만 전송 — userId는 Worker가 리포트 행의 소유자를 에코한 값 (계약)
            emitters.sendTo(message.userId(), eventNameFor(message.status()), event);
        } catch (RuntimeException e) {
            // 잘못된 이벤트 하나가 리스너를 죽이지 않도록 삼킨다
            log.warn("리포트 상태 이벤트 처리 실패: {}", record.getValue(), e);
        }
    }

    private String eventNameFor(String status) {
        return switch (status) {
            case "COMPLETED" -> EVENT_COMPLETED;
            case "FAILED" -> EVENT_FAILED;
            default -> EVENT_STATUS_CHANGED;
        };
    }
}
