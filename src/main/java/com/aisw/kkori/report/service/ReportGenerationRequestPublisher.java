package com.aisw.kkori.report.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.report.dto.ReportGenerationRequestedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 생성 요청 발행 — {@code report.generation.requested}의 Spring 측 유일한 발행 창구.
 *
 * <p>이 스트림의 발행자는 둘이다: 면접 도메인 에이전트(세션 정상 종료)와 Spring(FAILED 재생성).
 * 어느 발행자인지는 로그로 구분한다 — 여기의 발행 로그가 Spring(재생성) 쪽 기록이다.
 * 메시지 스키마는 {@link ReportGenerationRequestedMessage} 참조.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportGenerationRequestPublisher {

    private final StringRedisTemplate redisTemplate;

    /** 재생성 경로의 생성 요청 발행. reportId는 메시지에 실리지 않고 발행자 구분 로그에만 쓴다. */
    public void publishForRegeneration(long reportId, ReportGenerationRequestedMessage message) {
        try {
            redisTemplate.opsForStream()
                    .add(StreamRecords.mapBacked(message.toMap())
                            .withStreamKey(ReportGenerationRequestedMessage.STREAM_KEY));
            log.info("재생성으로 생성 요청 발행: reportId={}, sessionId={}", reportId, message.sessionId());
        } catch (Exception e) {
            log.error("재생성 생성 요청 발행 실패: reportId={}, sessionId={}", reportId, message.sessionId(), e);
            throw new BusinessException(ErrorCode.REPORT_GENERATION_REQUEST_FAILED);
        }
    }
}
