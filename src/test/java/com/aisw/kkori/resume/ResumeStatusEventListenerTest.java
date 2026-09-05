package com.aisw.kkori.resume;

import com.aisw.kkori.resume.dto.ResumeStatusChangedMessage;
import com.aisw.kkori.resume.dto.ResumeStatusEvent;
import com.aisw.kkori.resume.service.ResumeSseEmitters;
import com.aisw.kkori.resume.service.ResumeStatusEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
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
 * {@link ResumeStatusEventListener}의 페이로드 처리 단위 검증 — 잘못된 메시지 하나가 리스너 밖으로
 * 예외를 내보내지 않고(구독 유지), 상태별 이벤트 이름 분기가 맞는지 확인한다.
 */
class ResumeStatusEventListenerTest {

    private final ResumeSseEmitters emitters = mock(ResumeSseEmitters.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResumeStatusEventListener listener = new ResumeStatusEventListener(emitters, objectMapper);

    @ParameterizedTest(name = "페이로드: {0}")
    @ValueSource(strings = {
            "null",                                         // JSON 리터럴 null — Jackson이 예외 없이 null로 읽음
            "not-json",                                     // JSON 아님
            "{}",                                           // 필수 필드 전부 누락
            "{\"resumeId\":\"1\",\"status\":\"EMBEDDED\"}"  // userId 누락
    })
    @DisplayName("잘못된 페이로드는 예외 없이 버리고 SSE로 보내지 않는다")
    void invalidPayload_isDroppedWithoutThrowing(String body) {
        assertThatCode(() -> listener.onMessage(message(body), null)).doesNotThrowAnyException();
        verify(emitters, never()).sendTo(any(), any(), any());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "PARSED,   RESUME_ANALYSIS_STATUS_CHANGED",
            "EMBEDDED, RESUME_ANALYSIS_COMPLETED",
            "FAILED,   RESUME_ANALYSIS_FAILED"
    })
    @DisplayName("상태별로 이벤트 이름을 분기해 소유자에게 보낸다")
    void status_isSentToOwnerWithEventName(String status, String eventName) throws Exception {
        listener.onMessage(message(payload(12L, 4L, status, "m")), null);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(emitters).sendTo(eq(4L), eq(eventName), data.capture());
        assertThat(data.getValue()).isEqualTo(new ResumeStatusEvent(12L, status, "m"));
    }

    private String payload(long resumeId, long userId, String status, String message) throws Exception {
        return objectMapper.writeValueAsString(
                new ResumeStatusChangedMessage(resumeId, userId, status, message).toMap());
    }

    private static Message message(String body) {
        return new DefaultMessage(
                ResumeStatusChangedMessage.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
