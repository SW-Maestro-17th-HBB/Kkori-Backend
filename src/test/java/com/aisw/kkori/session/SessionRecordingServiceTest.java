package com.aisw.kkori.session;

import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.dto.AudioAnalysisRequestedMessage;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.session.service.AudioAnalysisRequestPublisher;
import com.aisw.kkori.session.service.SessionRecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 녹음 완료 처리의 실패 정책·순서 고정 (PRD interview-recording.md §발행 계약 — 2026-08-11 확정).
 *
 * <p>정상 관통·멱등(웹훅 중복)·미등록 egress는 통합 테스트({@code SessionRecordingIntegrationTest})가
 * 실물로 검증한다 — 여기는 통합으로 만들 수 없는 실패 경로(발행·기록 실패)와 <b>발행 → 기록
 * 순서</b>를 mock 상호작용으로 고정한다.
 */
class SessionRecordingServiceTest {

    private static final String EGRESS_ID = "EG_1";
    private static final String BUCKET = "kkori-rec";
    private static final String OBJECT_KEY = "recordings/room-r-123.ogg";

    private final InterviewSessionRepository sessionRepository = mock(InterviewSessionRepository.class);
    private final AudioAnalysisRequestPublisher publisher = mock(AudioAnalysisRequestPublisher.class);
    private final SessionRecordingService service = new SessionRecordingService(
            sessionRepository, publisher,
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC));

    private InterviewSession session;

    @BeforeEach
    void seedSession() {
        session = InterviewSession.pending(1L, null, InterviewType.THIRTY_MIN, Position.BACKEND, "room-r");
        ReflectionTestUtils.setField(session, "id", 42L);
        when(sessionRepository.findByEgressId(EGRESS_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.recordRecordingResult(anyLong(), anyString(), anyString(), any()))
                .thenReturn(1);
    }

    @Test
    @DisplayName("발행이 기록보다 먼저다 — PRD가 확정한 순서의 회귀 고정")
    void publishesBeforeRecording() {
        service.completeRecording(EGRESS_ID, BUCKET, OBJECT_KEY);

        InOrder order = inOrder(publisher, sessionRepository);
        order.verify(publisher).publish(new AudioAnalysisRequestedMessage(42L, BUCKET, OBJECT_KEY));
        order.verify(sessionRepository).recordRecordingResult(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("발행 실패는 기록을 생략하고 예외를 전파하지 않는다 (200 유지 — 재전송이 재발행 기회)")
    void publishFailureSkipsRecordingAndStaysQuiet() {
        doThrow(new RuntimeException("redis down")).when(publisher).publish(any());

        assertThatCode(() -> service.completeRecording(EGRESS_ID, BUCKET, OBJECT_KEY))
                .doesNotThrowAnyException();

        verify(sessionRepository, never()).recordRecordingResult(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("기록 실패는 전파된다 (500 → LiveKit 재전송 유도, 중복 발행은 워커 멱등이 흡수)")
    void recordingFailurePropagates() {
        when(sessionRepository.recordRecordingResult(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.completeRecording(EGRESS_ID, BUCKET, OBJECT_KEY))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("objectKey가 이미 기록된 세션은 발행·기록 모두 생략한다 (멱등 가드)")
    void alreadyRecordedSessionIsIdempotentNoop() {
        ReflectionTestUtils.setField(session, "recordingObjectKey", "recordings/earlier.ogg");

        service.completeRecording(EGRESS_ID, BUCKET, OBJECT_KEY);

        verifyNoInteractions(publisher);
        verify(sessionRepository, never()).recordRecordingResult(anyLong(), anyString(), anyString(), any());
    }
}
