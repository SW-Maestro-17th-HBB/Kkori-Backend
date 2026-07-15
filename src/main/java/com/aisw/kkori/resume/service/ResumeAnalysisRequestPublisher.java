package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 이력서 분석 요청 이벤트 발행 — {@code resume.parse.requested} 스트림의 유일한 발행 창구.
 *
 * <p>메시지 스키마는 {@link ResumeParseRequestedMessage} 참조. Stream은 소비자가 없어도
 * 메시지를 보존하므로 Worker 도입 전에도 발행이 유효하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAnalysisRequestPublisher {

    private final StringRedisTemplate redisTemplate;

    public void publish(ResumeParseRequestedMessage message) {
        try {
            redisTemplate.opsForStream()
                    .add(StreamRecords.mapBacked(message.toMap())
                            .withStreamKey(ResumeParseRequestedMessage.STREAM_KEY));
        } catch (Exception e) {
            log.error("분석 요청 발행 실패: resumeId={}", message.resumeId(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_REQUEST_FAILED);
        }
    }
}
