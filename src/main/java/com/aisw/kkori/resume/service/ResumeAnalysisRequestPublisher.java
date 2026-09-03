package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link ResumeAnalysisRequester}의 비동기 구현 — {@code resume.parse.requested} 스트림의
 * 유일한 발행 창구. {@code app.ai-dispatch.mode} 미설정 시의 기본 구현이다(matchIfMissing).
 *
 * <p>발행은 호출자의 트랜잭션 안에서 일어난다({@code dispatchInTransaction}) — 발행 실패 시
 * 예외로 트랜잭션 전체가 롤백되어 상태 변경이 남지 않는 것이 계약이다.
 * 메시지 스키마는 {@link ResumeParseRequestedMessage} 참조. Stream은 소비자가 없어도
 * 메시지를 보존하므로 Worker 도입 전에도 발행이 유효하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai-dispatch.mode", havingValue = "async", matchIfMissing = true)
public class ResumeAnalysisRequestPublisher implements ResumeAnalysisRequester {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void dispatchInTransaction(ResumeParseRequestedMessage message) {
        try {
            redisTemplate.opsForStream()
                    .add(StreamRecords.mapBacked(message.toMap())
                            .withStreamKey(ResumeParseRequestedMessage.STREAM_KEY));
        } catch (Exception e) {
            log.error("분석 요청 발행 실패: resumeId={}", message.resumeId(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_REQUEST_FAILED);
        }
    }

    @Override
    public void dispatchAfterCommit(ResumeParseRequestedMessage message) {
        // 비동기 모드는 커밋 후 할 일이 없다 — 발행은 트랜잭션 안에서 끝났다
    }
}
