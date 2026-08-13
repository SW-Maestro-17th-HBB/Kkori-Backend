package com.aisw.kkori.session.service;

import com.aisw.kkori.session.dto.AudioAnalysisRequestedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 음성 분석 요청 이벤트 발행 — {@code report.audio.analysis.requested} 스트림의 유일한 발행 창구
 * ({@code ResumeAnalysisRequestPublisher}와 동일 패턴 — PRD interview-recording.md 기타 요구사항).
 *
 * <p>메시지 스키마는 {@link AudioAnalysisRequestedMessage} 참조. Stream은 소비자가 없어도
 * 메시지를 보존하므로 Worker의 소비 구현 전에도 발행이 유효하다.
 *
 * <p>실패를 {@code ErrorCode}로 감싸지 않는다 — 이 발행은 webhook 경로 전용이라 API 응답으로
 * 표면화되지 않으며, 실패 정책(warn 후 기록 생략·200 유지)은 호출측
 * ({@link SessionRecordingService})이 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AudioAnalysisRequestPublisher {

    private final StringRedisTemplate redisTemplate;

    public void publish(AudioAnalysisRequestedMessage message) {
        redisTemplate.opsForStream()
                .add(StreamRecords.mapBacked(message.toMap())
                        .withStreamKey(AudioAnalysisRequestedMessage.STREAM_KEY));
    }
}
