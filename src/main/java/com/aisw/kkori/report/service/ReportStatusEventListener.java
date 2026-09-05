package com.aisw.kkori.report.service;

import com.aisw.kkori.global.sse.StatusChannelListener;
import com.aisw.kkori.report.dto.ReportStatusChangedMessage;
import com.aisw.kkori.report.dto.ReportStatusEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 상태 이벤트 채널 구독 → SSE push — {@code report.status.changed} 채널의 유일한 구독 창구.
 *
 * <p>메시지 스키마는 {@link ReportStatusChangedMessage} 참조. Worker가 JSON 문자열로 PUBLISH한 것을
 * 필드 맵으로 읽어 계약 record로 변환한다. Pub/Sub은 구독 중인 모든 인스턴스에 전달되므로 각 인스턴스는
 * 자기 메모리의 SSE 연결에만 보내고, 연결이 없으면 버린다 — SSE는 유실을 허용하고 복구는 REST가
 * 담당한다는 PRD §5 규칙.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportStatusEventListener implements StatusChannelListener {

    private static final String EVENT_STATUS_CHANGED = "REPORT_GENERATION_STATUS_CHANGED";
    private static final String EVENT_COMPLETED = "REPORT_GENERATION_COMPLETED";
    private static final String EVENT_FAILED = "REPORT_GENERATION_FAILED";
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {
    };

    private final ReportSseEmitters emitters;
    private final ObjectMapper objectMapper;

    @Override
    public String channel() {
        return ReportStatusChangedMessage.CHANNEL;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        Map<String, String> fields = Map.of();
        try {
            fields = objectMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), FIELDS);
            ReportStatusChangedMessage status = ReportStatusChangedMessage.from(fields);
            String eventName = eventNameFor(status.status());
            if (eventName == null) {
                // 계약(3종) 밖 상태는 프론트로 흘리지 않는다 — 발행 측 강제의 소비 측 방어선 (리뷰 반영)
                log.warn("계약에 없는 리포트 상태 — 전송 생략: reportId={}, status={}",
                        status.reportId(), status.status());
                return;
            }
            ReportStatusEvent event = new ReportStatusEvent(
                    status.reportId(), status.status(), status.message());
            // 소유자에게만 전송 — userId는 Worker가 리포트 행의 소유자를 에코한 값 (계약)
            emitters.sendTo(status.userId(), eventName, event);
        } catch (Exception e) {
            // 잘못된 이벤트 하나가 리스너를 죽이지 않도록 삼킨다. payload 원문은 남기지 않는다
            // — userId·실패 사유가 담기므로 진단에 필요한 최소 필드만 (리뷰 반영)
            log.warn("리포트 상태 이벤트 처리 실패: reportId={}, status={}",
                    fields.get("reportId"), fields.get("status"), e);
        }
    }

    /** 계약에 정의된 3종만 매핑한다 — 그 외(null 포함)는 null을 반환해 전송을 막는다. */
    private String eventNameFor(String status) {
        return switch (status) {
            case "PROCESSING" -> EVENT_STATUS_CHANGED;
            case "COMPLETED" -> EVENT_COMPLETED;
            case "FAILED" -> EVENT_FAILED;
            case null, default -> null;
        };
    }
}
