package com.aisw.kkori.report;

import com.aisw.kkori.report.dto.ReportStatusChangedMessage;
import com.aisw.kkori.report.dto.ReportStatusEvent;
import com.aisw.kkori.report.service.ReportSseEmitters;
import com.aisw.kkori.report.service.ReportStatusEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReportStatusEventListener}의 페이로드 처리 단위 검증 — 잘못된 메시지 하나가 리스너 밖으로
 * 예외를 내보내지 않고(구독 유지), 계약 3종만 SSE로 보내는지 확인한다.
 */
class ReportStatusEventListenerTest {

    private final ReportSseEmitters emitters = mock(ReportSseEmitters.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReportStatusEventListener listener = new ReportStatusEventListener(emitters, objectMapper);

    @ParameterizedTest(name = "페이로드: {0}")
    @ValueSource(strings = {
            "null",                                          // JSON 리터럴 null — Jackson이 예외 없이 null로 읽음
            "not-json",                                      // JSON 아님
            "{}",                                            // 필수 필드 전부 누락
            "{\"reportId\":\"1\",\"status\":\"COMPLETED\"}"  // userId 누락
    })
    @DisplayName("잘못된 페이로드는 예외 없이 버리고 SSE로 보내지 않는다")
    void invalidPayload_isDroppedWithoutThrowing(String body) {
        assertThatCode(() -> listener.onMessage(message(body), null)).doesNotThrowAnyException();
        verify(emitters, never()).sendTo(any(), any(), any());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "PROCESSING, REPORT_GENERATION_STATUS_CHANGED",
            "COMPLETED,  REPORT_GENERATION_COMPLETED",
            "FAILED,     REPORT_GENERATION_FAILED"
    })
    @DisplayName("계약 상태는 소유자에게 이벤트 이름을 분기해 보낸다")
    void contractStatus_isSentToOwner(String status, String eventName) throws Exception {
        listener.onMessage(message(payload(7L, 4L, status, "m")), null);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(emitters).sendTo(eq(4L), eq(eventName), data.capture());
        assertThat(data.getValue()).isEqualTo(new ReportStatusEvent(7L, status, "m"));
    }

    @Test
    @DisplayName("계약에 없는 상태(PENDING)는 보내지 않는다")
    void statusOutsideContract_isNotSent() throws Exception {
        listener.onMessage(message(payload(7L, 4L, "PENDING", "")), null);
        verify(emitters, never()).sendTo(any(), any(), any());
    }

    private String payload(long reportId, long userId, String status, String message) throws Exception {
        return objectMapper.writeValueAsString(
                new ReportStatusChangedMessage(reportId, userId, status, message).toMap());
    }

    private static Message message(String body) {
        return new DefaultMessage(
                ReportStatusChangedMessage.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
