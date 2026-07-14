package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 이력서 분석 요청 이벤트 발행.
 *
 * <p>Redis Stream({@value #STREAM_KEY})에 XADD하며, Python AI Worker가
 * Consumer Group으로 소비한다. Stream은 소비자가 없어도 메시지를 보존하므로
 * Worker 도입 전에도 발행이 유효하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAnalysisRequestPublisher {

    public static final String STREAM_KEY = "resume.parse.requested";

    private final StringRedisTemplate redisTemplate;

    public void publish(Long resumeId, Long userId, String bucket, String objectKey) {
        Map<String, String> payload = new HashMap<>();
        payload.put("resumeId", String.valueOf(resumeId));
        payload.put("userId", userId == null ? "" : String.valueOf(userId));
        payload.put("bucket", bucket);
        payload.put("objectKey", objectKey);

        try {
            redisTemplate.opsForStream()
                    .add(StreamRecords.mapBacked(payload).withStreamKey(STREAM_KEY));
        } catch (Exception e) {
            log.error("분석 요청 발행 실패: resumeId={}", resumeId, e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_REQUEST_FAILED);
        }
    }
}
